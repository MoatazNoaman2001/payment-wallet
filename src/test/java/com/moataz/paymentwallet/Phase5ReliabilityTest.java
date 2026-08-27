package com.moataz.paymentwallet;

import com.moataz.paymentwallet.account.*;
import com.moataz.paymentwallet.reliability.BalanceDriftView;
import com.moataz.paymentwallet.reliability.OutboxPublisher;
import com.moataz.paymentwallet.reliability.ReconciliationService;
import com.moataz.paymentwallet.transfer.*;
import com.moataz.paymentwallet.transfer.dto.CashRequest;
import com.moataz.paymentwallet.transfer.dto.TransferRequest;
import com.moataz.paymentwallet.transfer.dto.TransferResponse;
import com.moataz.paymentwallet.user.AppUser;
import com.moataz.paymentwallet.user.AppUserRepository;
import com.moataz.paymentwallet.user.Role;
import com.moataz.paymentwallet.user.RoleRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Timers off: a job firing mid-assertion would be a race, and the services are the unit under test. */
@SpringBootTest(properties = "reliability.scheduling.enabled=false")
class Phase5ReliabilityTest {

    @Autowired ReconciliationService reconciliationService;
    @Autowired OutboxPublisher outboxPublisher;
    @Autowired TransferService transferService;
    @Autowired CashService cashService;
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
        // funded through a real deposit, not by writing a balance: a balance with no
        // ledger behind it is exactly what reconciliation is supposed to flag
        aliceWallet = newAccount(alice, BigDecimal.ZERO);
        bobWallet = newAccount(newUser("bob"), BigDecimal.ZERO);
        cashService.deposit(aliceWallet.getAccountNumber(), newUser("cashier").getPublicId(),
                new CashRequest(new BigDecimal("500.0000"), "opening balance"),
                UUID.randomUUID().toString());
    }

    @AfterEach
    void tearDown() {
        testDataCleaner.deleteCreated(accountIds, userIds);
        accountIds.clear();
        userIds.clear();
    }

    @Test
    @DisplayName("a healthy ledger reports no drift, and a corrupted balance is caught")
    void detectsDrift() {
        send("120.0000");
        assertThat(driftFor(aliceWallet)).isEmpty();
        assertThat(driftFor(bobWallet)).isEmpty();

        // simulate the bug this job exists to catch: a balance edited outside the ledger
        Account tampered = accountRepository.findById(aliceWallet.getId()).orElseThrow();
        tampered.setBalance(tampered.getBalance().add(new BigDecimal("999.0000")));
        accountRepository.saveAndFlush(tampered);

        List<BalanceDriftView> drift = driftFor(aliceWallet);
        assertThat(drift).hasSize(1);
        BalanceDriftView row = drift.getFirst();
        assertThat(row.getBalance().subtract(row.getLedgerBalance()))
                .isEqualByComparingTo("999.0000");

        System.out.println(">>>>> drift detected: " + row.getAccountNumber()
                + " balance=" + row.getBalance() + " ledger=" + row.getLedgerBalance());

        // reporting does not repair: hiding the symptom would hide the bug
        assertThat(accountRepository.findById(aliceWallet.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(row.getBalance());
    }

    @Test
    @DisplayName("the outbox publisher stamps events once and never picks them up again")
    void publishesEachEventOnce() {
        TransferResponse first = send("10.0000");
        TransferResponse second = send("20.0000");

        assertThat(pendingFor(first)).isTrue();
        assertThat(pendingFor(second)).isTrue();

        int published = outboxPublisher.publishBatch(100);
        assertThat(published).isGreaterThanOrEqualTo(2);

        assertThat(pendingFor(first)).isFalse();
        assertThat(pendingFor(second)).isFalse();

        // a second run finds nothing: published_at is the idempotency marker
        assertThat(outboxPublisher.publishBatch(100)).isZero();
    }

    @Test
    @DisplayName("the batch size is respected, so a backlog is drained in chunks")
    void respectsBatchSize() {
        outboxPublisher.publishBatch(1000);          // clear anything already pending
        send("1.0000");
        send("2.0000");
        send("3.0000");

        assertThat(outboxPublisher.publishBatch(2)).isEqualTo(2);
        assertThat(outboxPublisher.publishBatch(2)).isEqualTo(1);
        assertThat(outboxPublisher.publishBatch(2)).isZero();
    }

    // ---------- fixtures ----------

    private List<BalanceDriftView> driftFor(Account account) {
        return reconciliationService.findDrift().stream()
                .filter(d -> d.getAccountNumber().equals(account.getAccountNumber()))
                .toList();
    }

    private boolean pendingFor(TransferResponse transfer) {
        return outboxEventRepository.findByAggregateIdOrderById(transfer.reference()).stream()
                .anyMatch(e -> e.getPublishedAt() == null);
    }

    private TransferResponse send(String amount) {
        return transferService.execute(new TransferRequest(
                aliceWallet.getAccountNumber(), bobWallet.getAccountNumber(),
                new BigDecimal(amount), "EGP", TransferType.P2P, "reliability test"),
                UUID.randomUUID().toString(), alice.getPublicId());
    }

    private AppUser newUser(String name) {
        AppUser user = new AppUser();
        user.setEmail(name + "-" + UUID.randomUUID() + "@example.com");
        user.setPhone("+8" + (System.nanoTime() % 1000000000000L));
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
}
