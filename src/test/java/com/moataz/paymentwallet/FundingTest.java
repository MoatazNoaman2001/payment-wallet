package com.moataz.paymentwallet;

import com.moataz.paymentwallet.account.AccountRepository;
import com.moataz.paymentwallet.account.AccountService;
import com.moataz.paymentwallet.account.AccountType;
import com.moataz.paymentwallet.account.dto.OpenAccountRequest;
import com.moataz.paymentwallet.common.error.BusinessRuleException;
import com.moataz.paymentwallet.funding.FundingService;
import com.moataz.paymentwallet.funding.PaymentIntentStatus;
import com.moataz.paymentwallet.funding.PaymentProvider;
import com.moataz.paymentwallet.funding.WebhookService;
import com.moataz.paymentwallet.funding.dto.FundingRequest;
import com.moataz.paymentwallet.funding.dto.PaymentIntentResponse;
import com.moataz.paymentwallet.funding.provider.SandboxAdapter;
import com.moataz.paymentwallet.funding.provider.WebhookVerificationException;
import com.moataz.paymentwallet.user.KycService;
import com.moataz.paymentwallet.user.KycTier;
import com.moataz.paymentwallet.user.UserService;
import com.moataz.paymentwallet.user.dto.KycSubmissionRequest;
import com.moataz.paymentwallet.user.dto.RegisterUserRequest;
import com.moataz.paymentwallet.user.dto.UserResponse;
import com.moataz.paymentwallet.user.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Money that arrives from, or leaves towards, somebody else's system.
 *
 * The rule every test here circles is the same one: a deposit reaches the ledger when the
 * provider says so, and never a moment earlier.
 */
@SpringBootTest
@Transactional
class FundingTest {

    @Autowired FundingService fundingService;
    @Autowired WebhookService webhookService;
    @Autowired SandboxAdapter sandboxAdapter;
    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepository;
    @Autowired UserService userService;
    @Autowired KycService kycService;
    @Autowired AppUserRepository userRepository;

    private UserResponse customer;
    private String wallet;
    private BigDecimal clearingAtStart;

    @BeforeEach
    void setUp() {
        customer = verified("funder");
        wallet = accountService.open(
                new OpenAccountRequest(customer.publicId(), "EGP", AccountType.WALLET, null),
                customer.publicId(), customer.publicId()).accountNumber();
        clearingAtStart = balanceOf("CLEARING-SANDBOX-EGP");
    }

    @Test
    @DisplayName("a deposit reaches no ledger until the provider confirms it")
    void depositWaitsForConfirmation() {
        PaymentIntentResponse intent = deposit("100.0000");

        assertThat(intent.status()).isEqualTo(PaymentIntentStatus.REQUIRES_ACTION);
        assertThat(intent.redirectUrl()).contains("/funding/sandbox/");
        assertThat(intent.transferReference()).isNull();
        assertThat(balanceOf(wallet)).isEqualByComparingTo("0.0000");

        confirm(intent, "succeeded");

        PaymentIntentResponse settled = fundingService.find(intent.reference());
        assertThat(settled.status()).isEqualTo(PaymentIntentStatus.SUCCEEDED);
        assertThat(settled.transferReference()).isNotNull();
        assertThat(balanceOf(wallet)).isEqualByComparingTo("100.0000");

        // double entry still holds: the provider's clearing account is down by the same amount
        assertThat(clearingMoved()).isEqualByComparingTo("-100.0000");
    }

    @Test
    @DisplayName("a replayed webhook credits nobody twice")
    void replayIsIgnored() {
        PaymentIntentResponse intent = deposit("40.0000");
        String body = event(intent, "succeeded");

        assertThat(deliver(body)).isEqualTo("processed");
        assertThat(balanceOf(wallet)).isEqualByComparingTo("40.0000");

        assertThat(deliver(body)).isEqualTo("duplicate");
        assertThat(balanceOf(wallet)).isEqualByComparingTo("40.0000");
    }

    @Test
    @DisplayName("a webhook that arrives twice under different event ids is still only paid once")
    void terminalStateIsTheSecondGuard() {
        PaymentIntentResponse intent = deposit("40.0000");

        deliver(event(intent, "succeeded"));
        // a fresh event id defeats the dedupe table, so the intent's own state has to hold
        deliver(event(intent, "succeeded"));

        assertThat(balanceOf(wallet)).isEqualByComparingTo("40.0000");
    }

    @Test
    @DisplayName("an unsigned webhook moves nothing")
    void unsignedWebhookIsRejected() {
        PaymentIntentResponse intent = deposit("75.0000");
        String body = event(intent, "succeeded");

        assertThatThrownBy(() -> webhookService.handle(PaymentProvider.SANDBOX, body,
                Map.of("x-sandbox-signature", "not-the-signature")))
                .isInstanceOf(WebhookVerificationException.class);

        assertThatThrownBy(() -> webhookService.handle(PaymentProvider.SANDBOX, body, Map.of()))
                .isInstanceOf(WebhookVerificationException.class);

        assertThat(balanceOf(wallet)).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("a declined deposit leaves the wallet untouched")
    void declinedDeposit() {
        PaymentIntentResponse intent = deposit("60.0000");
        confirm(intent, "failed");

        assertThat(fundingService.find(intent.reference()).status())
                .isEqualTo(PaymentIntentStatus.FAILED);
        assertThat(balanceOf(wallet)).isEqualByComparingTo("0.0000");
        assertThat(clearingMoved()).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("a withdrawal debits at once, so the money cannot be spent twice while it settles")
    void withdrawalReservesImmediately() {
        fund("500.0000");

        PaymentIntentResponse intent = withdraw("200.0000");

        assertThat(intent.status()).isEqualTo(PaymentIntentStatus.PENDING);
        assertThat(balanceOf(wallet)).isEqualByComparingTo("300.0000");

        confirm(intent, "succeeded");

        assertThat(fundingService.find(intent.reference()).status())
                .isEqualTo(PaymentIntentStatus.SUCCEEDED);
        // success moves nothing further: the debit already happened
        assertThat(balanceOf(wallet)).isEqualByComparingTo("300.0000");
    }

    @Test
    @DisplayName("a failed payout is returned with a compensating credit, not by editing the ledger")
    void failedPayoutIsReturned() {
        fund("500.0000");

        PaymentIntentResponse intent = withdraw("200.0000");
        assertThat(balanceOf(wallet)).isEqualByComparingTo("300.0000");

        confirm(intent, "failed");

        assertThat(fundingService.find(intent.reference()).status())
                .isEqualTo(PaymentIntentStatus.FAILED);
        assertThat(balanceOf(wallet)).isEqualByComparingTo("500.0000");
        assertThat(clearingMoved()).isEqualByComparingTo("-500.0000");
    }

    @Test
    @DisplayName("the same idempotency key never starts a second payment")
    void idempotentInitiation() {
        String key = UUID.randomUUID().toString();
        FundingRequest request = new FundingRequest(wallet, PaymentProvider.SANDBOX,
                new BigDecimal("30.0000"), null);

        PaymentIntentResponse first = fundingService.deposit(request, customer.publicId(), key);
        PaymentIntentResponse second = fundingService.deposit(request, customer.publicId(), key);

        assertThat(second.reference()).isEqualTo(first.reference());
    }

    @Test
    @DisplayName("a provider with no credentials is refused up front, not deep inside an HTTP call")
    void unconfiguredProviderIsRefused() {
        assertThatThrownBy(() -> fundingService.deposit(
                new FundingRequest(wallet, PaymentProvider.STRIPE, new BigDecimal("10.0000"), null),
                customer.publicId(), UUID.randomUUID().toString()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("STRIPE is not configured");
    }

    @Test
    @DisplayName("the crypto gateway cannot pay out, and says so")
    void cryptoCannotPayOut() {
        assertThatThrownBy(() -> fundingService.withdraw(
                new FundingRequest(wallet, PaymentProvider.CRYPTO, new BigDecimal("10.0000"), null),
                customer.publicId(), UUID.randomUUID().toString()))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("the KYC daily ceiling still applies to a provider withdrawal")
    void kycCeilingAppliesToPayouts() {
        UserResponse basic = verified("basiccap", KycTier.BASIC);
        String basicWallet = accountService.open(
                new OpenAccountRequest(basic.publicId(), "EGP", AccountType.WALLET, null),
                basic.publicId(), basic.publicId()).accountNumber();
        seed(basicWallet, new BigDecimal("90000.0000"));

        assertThatThrownBy(() -> fundingService.withdraw(
                new FundingRequest(basicWallet, PaymentProvider.SANDBOX,
                        new BigDecimal("6000.0000"), null),
                basic.publicId(), UUID.randomUUID().toString()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("BASIC verification");
    }

    @Test
    @DisplayName("you cannot fund somebody else's account")
    void cannotFundAnotherPersonsAccount() {
        UserResponse stranger = verified("stranger");

        assertThatThrownBy(() -> fundingService.deposit(
                new FundingRequest(wallet, PaymentProvider.SANDBOX, new BigDecimal("10.0000"), null),
                stranger.publicId(), UUID.randomUUID().toString()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("belongs to somebody else");
    }

    @Test
    @DisplayName("a settlement account is not fundable through a provider")
    void settlementAccountsAreNotFundable() {
        assertThatThrownBy(() -> fundingService.deposit(
                new FundingRequest("SYSTEM-EGP", PaymentProvider.SANDBOX,
                        new BigDecimal("10.0000"), null),
                customer.publicId(), UUID.randomUUID().toString()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Settlement accounts");
    }

    // ---------- fixtures ----------

    private PaymentIntentResponse deposit(String amount) {
        return fundingService.deposit(
                new FundingRequest(wallet, PaymentProvider.SANDBOX, new BigDecimal(amount), null),
                customer.publicId(), UUID.randomUUID().toString());
    }

    private PaymentIntentResponse withdraw(String amount) {
        return fundingService.withdraw(
                new FundingRequest(wallet, PaymentProvider.SANDBOX, new BigDecimal(amount), null),
                customer.publicId(), UUID.randomUUID().toString());
    }

    /** Money in the wallet, put there the only way it can be: a confirmed deposit. */
    private void fund(String amount) {
        confirm(deposit(amount), "succeeded");
    }

    private void confirm(PaymentIntentResponse intent, String outcome) {
        deliver(event(intent, outcome));
    }

    private String deliver(String body) {
        return webhookService.handle(PaymentProvider.SANDBOX, body,
                Map.of("x-sandbox-signature", sandboxAdapter.sign(body)));
    }

    private String event(PaymentIntentResponse intent, String outcome) {
        return """
                {"id":"evt_%s","type":"sandbox.payment.%s","reference":"%s","status":"%s"}"""
                .formatted(UUID.randomUUID().toString().replace("-", ""),
                           outcome, intent.providerReference(), outcome);
    }

    private UserResponse verified(String name) {
        return verified(name, KycTier.VERIFIED);
    }

    private UserResponse verified(String name, KycTier tier) {
        UserResponse user = userService.register(new RegisterUserRequest(
                name + "-" + UUID.randomUUID() + "@example.com",
                "+2" + (System.nanoTime() % 1000000000000L),
                "supersecret1", name));
        kycService.submit(user.publicId(), new KycSubmissionRequest(
                String.valueOf(System.nanoTime() % 100000000000000L),
                LocalDate.of(1990, 1, 1), "Cairo"));
        return kycService.approve(user.publicId(), tier, "test", reviewer());
    }

    private void seed(String accountNumber, BigDecimal balance) {
        var account = accountRepository.findByAccountNumber(accountNumber).orElseThrow();
        account.setBalance(balance);
        accountRepository.saveAndFlush(account);
    }

    private UUID reviewer() {
        return userRepository.findByEmailWithRoles("compliance@paymentwallet.local")
                .orElseThrow(() -> new IllegalStateException("compliance staff not seeded"))
                .getPublicId();
    }

    private BigDecimal balanceOf(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber).orElseThrow().getBalance();
    }

    private BigDecimal clearingMoved() {
        return balanceOf("CLEARING-SANDBOX-EGP").subtract(clearingAtStart);
    }
}
