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
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Not @Transactional: the concurrency test needs real, separate transactions,
 * so everything created here is deleted in @AfterEach instead of rolled back.
 */
@SpringBootTest
class Phase2TransferEngineTest {

    @Autowired TransferService transferService;
    @Autowired TransferRepository transferRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired AppUserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired CurrencyRepository currencyRepository;
    @Autowired TestDataCleaner testDataCleaner;

    private final List<Long> createdAccountIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    private AppUser alice;
    private AppUser bob;
    private Account aliceEgp;
    private Account bobEgp;

    @BeforeEach
    void setUp() {
        alice = newUser("alice");
        bob = newUser("bob");
        aliceEgp = newAccount(alice, "EGP", new BigDecimal("50.0000"));
        bobEgp = newAccount(bob, "EGP", BigDecimal.ZERO);
    }

    @AfterEach
    void tearDown() {
        testDataCleaner.deleteCreated(createdAccountIds, createdUserIds);
        createdAccountIds.clear();
        createdUserIds.clear();
    }

    @Test
    @DisplayName("a posted transfer writes two balanced legs and one outbox event")
    void postsDoubleEntry() {
        TransferResponse response = transferService.execute(
                request(new BigDecimal("20.0000")), UUID.randomUUID().toString(), alice.getPublicId());

        assertThat(response.status()).isEqualTo(TransferStatus.POSTED);
        assertThat(response.reference()).startsWith("TRF");
        assertThat(response.postedAt()).isNotNull();

        Transfer transfer = transferRepository.findByReference(response.reference()).orElseThrow();
        List<LedgerEntry> legs = ledgerEntryRepository.findByTransferIdOrderById(transfer.getId());

        assertThat(legs).hasSize(2);
        BigDecimal debits = sum(legs, LedgerDirection.DEBIT);
        BigDecimal credits = sum(legs, LedgerDirection.CREDIT);
        assertThat(debits).isEqualByComparingTo(credits);

        assertThat(balanceOf(aliceEgp)).isEqualByComparingTo("30.0000");
        assertThat(balanceOf(bobEgp)).isEqualByComparingTo("20.0000");

        assertThat(outboxEventRepository.findByAggregateIdOrderById(response.reference()))
                .singleElement()
                .extracting(OutboxEvent::getEventType)
                .isEqualTo("TransferPosted");
    }

    @Test
    @DisplayName("balance always equals the sum of its ledger entries")
    void balanceReconcilesWithLedger() {
        transferService.execute(request(new BigDecimal("12.5000")), UUID.randomUUID().toString(), alice.getPublicId());
        transferService.execute(request(new BigDecimal("7.5000")), UUID.randomUUID().toString(), alice.getPublicId());

        assertThat(ledgerEntryRepository.balanceFromLedger(bobEgp.getId()))
                .isEqualByComparingTo(balanceOf(bobEgp));
        assertThat(ledgerEntryRepository.balanceFromLedger(aliceEgp.getId()).negate())
                .isEqualByComparingTo(new BigDecimal("20.0000"));
    }

    @Test
    @DisplayName("retrying with the same Idempotency-Key returns the original, money moves once")
    void isIdempotent() {
        String key = UUID.randomUUID().toString();
        TransferRequest request = request(new BigDecimal("10.0000"));

        TransferResponse first = transferService.execute(request, key, alice.getPublicId());
        TransferResponse retry = transferService.execute(request, key, alice.getPublicId());

        assertThat(retry.reference()).isEqualTo(first.reference());
        assertThat(myTransfers()).isEqualTo(1);
        assertThat(balanceOf(aliceEgp)).isEqualByComparingTo("40.0000");
    }

    @Test
    @DisplayName("insufficient funds, currency mismatch and self-transfer are rejected")
    void rejectsInvalidTransfers() {
        assertThatThrownBy(() -> transferService.execute(
                request(new BigDecimal("500.0000")), UUID.randomUUID().toString(), alice.getPublicId()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Insufficient funds");

        Account aliceUsd = newAccount(alice, "USD", new BigDecimal("100.0000"));
        TransferRequest crossCurrency = new TransferRequest(
                aliceUsd.getAccountNumber(), bobEgp.getAccountNumber(),
                new BigDecimal("5.0000"), "USD", TransferType.P2P, null);
        assertThatThrownBy(() -> transferService.execute(crossCurrency, UUID.randomUUID().toString(), alice.getPublicId()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Currency mismatch", alice.getPublicId());

        TransferRequest toSelf = new TransferRequest(
                aliceEgp.getAccountNumber(), aliceEgp.getAccountNumber(),
                new BigDecimal("1.0000"), "EGP", TransferType.P2P, null);
        assertThatThrownBy(() -> transferService.execute(toSelf, UUID.randomUUID().toString(), alice.getPublicId()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("must differ", alice.getPublicId());

        assertThat(balanceOf(aliceEgp)).isEqualByComparingTo("50.0000");
    }

    @Test
    @DisplayName("100 parallel transfers of 1 EGP from a 50 EGP account: exactly 50 succeed")
    void doesNotOversellUnderConcurrency() throws Exception {
        int attempts = 100;
        ExecutorService pool = Executors.newFixedThreadPool(25);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < attempts; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    transferService.execute(request(new BigDecimal("1.0000")),
                                            UUID.randomUUID().toString(), alice.getPublicId());
                    succeeded.incrementAndGet();
                } catch (BusinessRuleException e) {
                    rejected.incrementAndGet();
                } catch (Exception e) {
                    // any other failure is a real bug, let the assertions below catch it
                }
            });
        }

        startGate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();

        System.out.println(">>>>> succeeded=" + succeeded.get() + " rejected=" + rejected.get()
                + " finalBalance=" + balanceOf(aliceEgp));

        assertThat(succeeded.get()).isEqualTo(50);
        assertThat(rejected.get()).isEqualTo(50);
        assertThat(balanceOf(aliceEgp)).isEqualByComparingTo("0.0000");
        assertThat(balanceOf(bobEgp)).isEqualByComparingTo("50.0000");
        assertThat(myTransfers()).isEqualTo(50);
        assertThat(myLedgerEntries()).isEqualTo(100);
        assertThat(ledgerEntryRepository.balanceFromLedger(aliceEgp.getId()))
                .isEqualByComparingTo("-50.0000");
    }

    // ---------- fixtures ----------

    private TransferRequest request(BigDecimal amount) {
        return new TransferRequest(aliceEgp.getAccountNumber(),
                bobEgp.getAccountNumber(), amount, "EGP", TransferType.P2P, "test");
    }

    private AppUser newUser(String name) {
        AppUser user = new AppUser();
        user.setEmail(name + "-" + UUID.randomUUID() + "@example.com");
        user.setPhone("+2" + (System.nanoTime() % 1000000000000L));
        user.setFullName(name);
        user.setPasswordHash("x");
        user.addRole(roleRepository.findByName(Role.CUSTOMER).orElseThrow());
        AppUser saved = userRepository.saveAndFlush(user);
        createdUserIds.add(saved.getId());
        return saved;
    }

    /** Seeds a balance directly. Real money can only arrive through a transfer. */
    private Account newAccount(AppUser owner, String currencyCode, BigDecimal balance) {
        Account account = new Account();
        account.setAccountNumber("PW" + String.format("%016d", System.nanoTime() % 10000000000000000L));
        account.setUser(owner);
        account.setCurrency(currencyRepository.findById(currencyCode).orElseThrow());
        account.setType(AccountType.WALLET);
        account.setStatus(AccountStatus.ACTIVE);
        account.setBalance(balance);
        Account saved = accountRepository.saveAndFlush(account);
        createdAccountIds.add(saved.getId());
        return saved;
    }

    private BigDecimal balanceOf(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }

    private BigDecimal sum(List<LedgerEntry> legs, LedgerDirection direction) {
        return legs.stream()
                .filter(e -> e.getDirection() == direction)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Scoped to this test's accounts: the database is shared with local development. */
    private long myTransfers() {
        return transferRepository
                .findBySourceAccountIdInOrDestAccountIdIn(createdAccountIds, createdAccountIds).size();
    }

    private long myLedgerEntries() {
        return transferRepository
                .findBySourceAccountIdInOrDestAccountIdIn(createdAccountIds, createdAccountIds).stream()
                .mapToLong(t -> ledgerEntryRepository.findByTransferIdOrderById(t.getId()).size())
                .sum();
    }

}
