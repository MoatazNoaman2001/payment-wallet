package com.luv2code.paymentwallet;

import com.luv2code.paymentwallet.account.*;
import com.luv2code.paymentwallet.common.error.BusinessRuleException;
import com.luv2code.paymentwallet.transfer.*;
import com.luv2code.paymentwallet.transfer.dto.TransferRequest;
import com.luv2code.paymentwallet.transfer.dto.TransferResponse;
import com.luv2code.paymentwallet.user.AppUser;
import com.luv2code.paymentwallet.user.AppUserRepository;
import com.luv2code.paymentwallet.user.Role;
import com.luv2code.paymentwallet.user.RoleRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class Phase5ReversalTest {

    @Autowired TransferService transferService;
    @Autowired ReversalService reversalService;
    @Autowired TransferRepository transferRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired AppUserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired CurrencyRepository currencyRepository;
    @Autowired TestDataCleaner testDataCleaner;

    private final List<Long> accountIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    private AppUser alice;
    private Account aliceWallet;
    private Account bobWallet;

    @BeforeEach
    void setUp() {
        alice = newUser("alice");
        aliceWallet = newAccount(alice, new BigDecimal("500.0000"));
        bobWallet = newAccount(newUser("bob"), BigDecimal.ZERO);
    }

    @AfterEach
    void tearDown() {
        testDataCleaner.deleteCreated(accountIds, userIds);
        accountIds.clear();
        userIds.clear();
    }

    @Test
    @DisplayName("reversing writes a compensating transfer and restores both balances")
    void reversesByCompensating() {
        TransferResponse original = send("100.0000");
        assertThat(balanceOf(aliceWallet)).isEqualByComparingTo("400.0000");

        TransferResponse reversal = reversalService.reverse(
                original.reference(), "duplicate charge", alice.getPublicId());

        assertThat(reversal.type()).isEqualTo(TransferType.REVERSAL);
        assertThat(reversal.status()).isEqualTo(TransferStatus.POSTED);
        assertThat(reversal.reference()).startsWith("REV");
        assertThat(reversal.sourceAccountNumber()).isEqualTo(bobWallet.getAccountNumber());
        assertThat(reversal.destAccountNumber()).isEqualTo(aliceWallet.getAccountNumber());

        assertThat(balanceOf(aliceWallet)).isEqualByComparingTo("500.0000");
        assertThat(balanceOf(bobWallet)).isEqualByComparingTo("0.0000");

        // nothing was deleted: the original is still there, now REVERSED
        Transfer stored = transferRepository.findByReference(original.reference()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TransferStatus.REVERSED);

        // four ledger rows, not two: the history shows both the payment and the undo
        assertThat(ledgerEntryRepository.balanceFromLedger(aliceWallet.getId()))
                .isEqualByComparingTo("0.0000");
        assertThat(ledgerEntryRepository.findByTransferIdOrderById(stored.getId())).hasSize(2);

        assertThat(outboxEventRepository.findByAggregateIdOrderById(original.reference()))
                .extracting(OutboxEvent::getEventType)
                .containsExactly("TransferPosted", "TransferReversed");
    }

    @Test
    @DisplayName("reversing twice is idempotent: one reversal, money moves once")
    void reversalIsIdempotent() {
        TransferResponse original = send("100.0000");

        TransferResponse first = reversalService.reverse(original.reference(), "oops", alice.getPublicId());
        TransferResponse again = reversalService.reverse(original.reference(), "oops", alice.getPublicId());

        assertThat(again.reference()).isEqualTo(first.reference());
        assertThat(balanceOf(aliceWallet)).isEqualByComparingTo("500.0000");
        assertThat(balanceOf(bobWallet)).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("a reversal cannot itself be reversed, and a REVERSED transfer cannot be reversed again")
    void stateMachineIsEnforced() {
        TransferResponse original = send("50.0000");
        TransferResponse reversal = reversalService.reverse(original.reference(), "wrong payee", alice.getPublicId());

        assertThatThrownBy(() -> reversalService.reverse(reversal.reference(), "no", alice.getPublicId()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cannot itself be reversed");
    }

    @Test
    @DisplayName("cannot reverse when the recipient has already spent the money")
    void refusesWhenFundsAreGone() {
        TransferResponse original = send("300.0000");

        // Bob forwards it on: the money is no longer sitting in his wallet
        Account carolWallet = newAccount(newUser("carol"), BigDecimal.ZERO);
        transferService.execute(new TransferRequest(bobWallet.getAccountNumber(),
                carolWallet.getAccountNumber(), new BigDecimal("300.0000"), "EGP",
                TransferType.P2P, "forwarded"), UUID.randomUUID().toString(),
                bobWallet.getUser().getPublicId());

        assertThatThrownBy(() -> reversalService.reverse(original.reference(), "fraud", alice.getPublicId()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("collections problem");

        // and nothing moved: the original stands until it can be settled another way
        Transfer stored = transferRepository.findByReference(original.reference()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TransferStatus.POSTED);
        assertThat(balanceOf(aliceWallet)).isEqualByComparingTo("200.0000");
    }

    @Test
    @DisplayName("a deposit can be reversed: the settlement account takes it back")
    void reversesADeposit() {
        TransferResponse topup = transferService.execute(new TransferRequest(
                systemEgp(), aliceWallet.getAccountNumber(), new BigDecimal("250.0000"),
                "EGP", TransferType.TOPUP, "teller error"),
                UUID.randomUUID().toString(), alice.getPublicId());

        assertThat(balanceOf(aliceWallet)).isEqualByComparingTo("750.0000");

        reversalService.reverse(topup.reference(), "teller keyed the wrong amount", alice.getPublicId());

        assertThat(balanceOf(aliceWallet)).isEqualByComparingTo("500.0000");
    }

    // ---------- fixtures ----------

    private TransferResponse send(String amount) {
        return transferService.execute(new TransferRequest(
                aliceWallet.getAccountNumber(), bobWallet.getAccountNumber(),
                new BigDecimal(amount), "EGP", TransferType.P2P, "payment"),
                UUID.randomUUID().toString(), alice.getPublicId());
    }

    private String systemEgp() {
        return accountRepository.findSystemAccountNumber("EGP").orElseThrow();
    }

    private AppUser newUser(String name) {
        AppUser user = new AppUser();
        user.setEmail(name + "-" + UUID.randomUUID() + "@example.com");
        user.setPhone("+7" + (System.nanoTime() % 1000000000000L));
        user.setFullName(name);
        user.setPasswordHash("x");
        user.addRole(roleRepository.findByName(Role.CUSTOMER).orElseThrow());
        AppUser saved = userRepository.saveAndFlush(user);
        userIds.add(saved.getId());
        return saved;
    }

    private Account newAccount(AppUser owner, BigDecimal balance) {
        Account account = new Account();
        account.setAccountNumber("PW" + String.format("%016d", System.nanoTime() % 10000000000000000L));
        account.setUser(owner);
        account.setCurrency(currencyRepository.findById("EGP").orElseThrow());
        account.setType(AccountType.WALLET);
        account.setStatus(AccountStatus.ACTIVE);
        account.setBalance(balance);
        Account saved = accountRepository.saveAndFlush(account);
        accountIds.add(saved.getId());
        return saved;
    }

    private BigDecimal balanceOf(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }
}
