package com.moataz.paymentwallet;

import com.moataz.paymentwallet.account.Account;
import com.moataz.paymentwallet.account.AccountRepository;
import com.moataz.paymentwallet.account.AccountService;
import com.moataz.paymentwallet.account.AccountType;
import com.moataz.paymentwallet.account.dto.OpenAccountRequest;
import com.moataz.paymentwallet.common.error.BusinessRuleException;
import com.moataz.paymentwallet.common.error.DuplicateResourceException;
import com.moataz.paymentwallet.transfer.TransferService;
import com.moataz.paymentwallet.transfer.TransferType;
import com.moataz.paymentwallet.transfer.dto.TransferRequest;
import com.moataz.paymentwallet.user.AppUserRepository;
import com.moataz.paymentwallet.user.KycService;
import com.moataz.paymentwallet.user.KycTier;
import com.moataz.paymentwallet.user.UserService;
import com.moataz.paymentwallet.user.UserStatus;
import com.moataz.paymentwallet.user.dto.CounterRegistrationRequest;
import com.moataz.paymentwallet.user.dto.KycProfileResponse;
import com.moataz.paymentwallet.user.dto.KycSubmissionRequest;
import com.moataz.paymentwallet.user.dto.RegisterUserRequest;
import com.moataz.paymentwallet.user.dto.UserResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Onboarding: who keys a customer in, who is allowed to believe them, and what the
 * resulting verification tier permits.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class KycAndOnboardingTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired KycService kycService;
    @Autowired AccountService accountService;
    @Autowired TransferService transferService;
    @Autowired AppUserRepository userRepository;
    @Autowired AccountRepository accountRepository;

    @Test
    @DisplayName("registering yourself leaves you PENDING with nothing on file")
    void registrationLeavesNothingOnFile() {
        UserResponse user = register("self");

        assertThat(user.status()).isEqualTo(UserStatus.PENDING);
        assertThat(kycService.find(user.publicId())).isEmpty();
        assertThat(userService.registrarNameOf(user.publicId())).isNull();
    }

    @Test
    @DisplayName("submitted details wait for review: still PENDING, still BASIC")
    void submissionIsNotVerification() {
        UserResponse user = register("submitter");

        KycProfileResponse profile = kycService.submit(user.publicId(), submission());

        assertThat(profile.reviewed()).isFalse();
        assertThat(profile.tier()).isEqualTo(KycTier.BASIC);
        assertThat(userService.findByPublicId(user.publicId()).status()).isEqualTo(UserStatus.PENDING);
    }

    @Test
    @DisplayName("the national id comes back masked, never in full")
    void nationalIdIsMasked() {
        UserResponse user = register("masked");
        KycProfileResponse profile = kycService.submit(user.publicId(),
                new KycSubmissionRequest("29001011234567", LocalDate.of(1990, 1, 1), "Cairo"));

        assertThat(profile.nationalId()).isEqualTo("**********4567");
        assertThat(profile.nationalId()).doesNotContain("2900101");
    }

    @Test
    @DisplayName("compliance approval sets the tier and activates the holder in one step")
    void approvalActivates() {
        UserResponse user = register("approved");
        kycService.submit(user.publicId(), submission());

        UserResponse reviewed = kycService.approve(
                user.publicId(), KycTier.VERIFIED, "id checked in branch", reviewer());

        assertThat(reviewed.status()).isEqualTo(UserStatus.ACTIVE);

        KycProfileResponse profile = kycService.find(user.publicId()).orElseThrow();
        assertThat(profile.reviewed()).isTrue();
        assertThat(profile.tier()).isEqualTo(KycTier.VERIFIED);
        assertThat(profile.reviewedBy()).isNotNull();
        assertThat(profile.reviewNote()).isEqualTo("id checked in branch");
    }

    @Test
    @DisplayName("there is nothing to approve until details are submitted")
    void cannotApproveAnEmptyFile() {
        UserResponse user = register("empty");

        assertThatThrownBy(() -> kycService.approve(user.publicId(), KycTier.VERIFIED, null, reviewer()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("has not submitted identity details");
    }

    @Test
    @DisplayName("nobody may verify their own identity")
    void cannotApproveYourself() {
        UserResponse user = register("selfapprove");
        kycService.submit(user.publicId(), submission());

        assertThatThrownBy(() -> kycService.approve(
                user.publicId(), KycTier.ENHANCED, null, user.publicId()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("your own identity");
    }

    @Test
    @DisplayName("a teller may register a walk-in but may not vouch for them")
    void tellerCannotApprove() throws Exception {
        UserResponse walkIn = userService.registerAtCounter(
                new CounterRegistrationRequest(registration("walkin"), submission()),
                staff("teller@paymentwallet.local"));

        assertThat(walkIn.status()).isEqualTo(UserStatus.PENDING);
        assertThat(userService.registrarNameOf(walkIn.publicId())).isEqualTo("Demo Teller");

        mockMvc.perform(post("/api/users/" + walkIn.publicId() + "/kyc-review")
                        .with(asRole(staff("teller@paymentwallet.local"), "ROLE_TELLER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tier\": \"ENHANCED\"}"))
               .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/users/" + walkIn.publicId() + "/kyc-review")
                        .with(asRole(staff("compliance@paymentwallet.local"), "ROLE_COMPLIANCE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tier\": \"VERIFIED\"}"))
               .andExpect(status().isOk());
    }

    @Test
    @DisplayName("one national id belongs to one person")
    void nationalIdIsUnique() {
        UserResponse first = register("first");
        UserResponse second = register("second");
        KycSubmissionRequest sameId = submission();

        kycService.submit(first.publicId(), sameId);

        assertThatThrownBy(() -> kycService.submit(second.publicId(), sameId))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("already belongs to another account holder");
    }

    @Test
    @DisplayName("an applicant under 18 is refused")
    void underageIsRefused() {
        UserResponse user = register("child");

        assertThatThrownBy(() -> kycService.submit(user.publicId(),
                new KycSubmissionRequest(nationalId(), LocalDate.now().minusYears(12), null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("at least 18");
    }

    @Test
    @DisplayName("verified details can no longer be edited by the holder")
    void verifiedDetailsAreFrozen() {
        UserResponse user = register("frozen");
        kycService.submit(user.publicId(), submission());
        kycService.approve(user.publicId(), KycTier.VERIFIED, null, reviewer());

        assertThatThrownBy(() -> kycService.submit(user.publicId(), submission()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already been verified");
    }

    @Test
    @DisplayName("the verification tier caps what may leave in a day")
    void tierCapsTheDay() {
        UserResponse sender = activated("capped", KycTier.BASIC);
        UserResponse recipient = activated("payee", KycTier.VERIFIED);

        Account from = fundedWallet(sender, new BigDecimal("90000.0000"));
        Account to = wallet(recipient);

        assertThatThrownBy(() -> transferService.execute(
                new TransferRequest(from.getAccountNumber(), to.getAccountNumber(),
                        new BigDecimal("6000.0000"), "EGP", TransferType.P2P, "over the tier"),
                UUID.randomUUID().toString(), sender.publicId()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Daily limit exceeded")
                .hasMessageContaining("BASIC verification");

        // the same money moves once the tier is raised, so the block was the tier and nothing else
        kycService.approve(sender.publicId(), KycTier.VERIFIED, "upgraded", reviewer());

        assertThat(transferService.execute(
                new TransferRequest(from.getAccountNumber(), to.getAccountNumber(),
                        new BigDecimal("6000.0000"), "EGP", TransferType.P2P, "within the tier"),
                UUID.randomUUID().toString(), sender.publicId()).status().name())
                .isEqualTo("POSTED");
    }

    @Test
    @DisplayName("the tighter of the account limit and the tier wins")
    void accountLimitStillApplies() {
        UserResponse sender = activated("tight", KycTier.ENHANCED);
        UserResponse recipient = activated("tightpayee", KycTier.ENHANCED);

        Account from = fundedWallet(sender, new BigDecimal("9000.0000"), new BigDecimal("100.0000"));
        Account to = wallet(recipient);

        assertThatThrownBy(() -> transferService.execute(
                new TransferRequest(from.getAccountNumber(), to.getAccountNumber(),
                        new BigDecimal("500.0000"), "EGP", TransferType.P2P, "over the account limit"),
                UUID.randomUUID().toString(), sender.publicId()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("account limit");
    }

    @Test
    @DisplayName("compliance reads every customer file but does not work the counter")
    void complianceReadsButDoesNotAct() throws Exception {
        UserResponse holder = activated("readonly", KycTier.VERIFIED);
        UUID reviewer = staff("compliance@paymentwallet.local");

        mockMvc.perform(get("/users").accept(MediaType.TEXT_HTML)
                        .with(asRole(reviewer, "ROLE_COMPLIANCE")))
               .andExpect(status().isOk());

        mockMvc.perform(get("/users/" + holder.publicId()).accept(MediaType.TEXT_HTML)
                        .with(asRole(reviewer, "ROLE_COMPLIANCE")))
               .andExpect(status().isOk());

        mockMvc.perform(get("/users/new").accept(MediaType.TEXT_HTML)
                        .with(asRole(reviewer, "ROLE_COMPLIANCE")))
               .andExpect(status().isForbidden());

        mockMvc.perform(post("/users/" + holder.publicId() + "/accounts")
                        .param("currencyCode", "EGP").param("type", "WALLET")
                        .with(asRole(reviewer, "ROLE_COMPLIANCE")).with(csrf()))
               .andExpect(status().isForbidden());
    }

    // ---------- fixtures ----------

    private UserResponse register(String name) {
        return userService.register(registration(name));
    }

    private RegisterUserRequest registration(String name) {
        return new RegisterUserRequest(
                name + "-" + UUID.randomUUID() + "@example.com",
                "+2" + (System.nanoTime() % 1000000000000L),
                "supersecret1",
                name);
    }

    private UserResponse activated(String name, KycTier tier) {
        UserResponse user = register(name);
        kycService.submit(user.publicId(), submission());
        return kycService.approve(user.publicId(), tier, null, reviewer());
    }

    private KycSubmissionRequest submission() {
        return new KycSubmissionRequest(nationalId(), LocalDate.of(1990, 1, 1), "Cairo");
    }

    private static String nationalId() {
        return String.valueOf(System.nanoTime() % 100000000000000L);
    }

    private Account wallet(UserResponse owner) {
        return fundedWallet(owner, BigDecimal.ZERO);
    }

    private Account fundedWallet(UserResponse owner, BigDecimal balance) {
        return fundedWallet(owner, balance, null);
    }

    private Account fundedWallet(UserResponse owner, BigDecimal balance, BigDecimal dailyLimit) {
        String number = accountService.open(
                new OpenAccountRequest(owner.publicId(), "EGP", AccountType.WALLET, dailyLimit),
                owner.publicId(), owner.publicId()).accountNumber();
        Account account = accountRepository.findByAccountNumber(number).orElseThrow();
        account.setBalance(balance);
        return accountRepository.saveAndFlush(account);
    }

    private UUID reviewer() {
        return staff("compliance@paymentwallet.local");
    }

    private UUID staff(String email) {
        return userRepository.findByEmailWithRoles(email)
                .orElseThrow(() -> new IllegalStateException("Staff account missing: " + email))
                .getPublicId();
    }

    private static RequestPostProcessor asRole(UUID publicId, String role) {
        return jwt().jwt(builder -> builder.subject(publicId.toString()))
                    .authorities(new SimpleGrantedAuthority(role));
    }
}
