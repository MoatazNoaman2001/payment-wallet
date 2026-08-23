package com.moataz.paymentwallet;

import com.moataz.paymentwallet.account.*;
import com.moataz.paymentwallet.common.error.BusinessRuleException;
import com.moataz.paymentwallet.transfer.*;
import com.moataz.paymentwallet.transfer.dto.TransferRequest;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/** Same 100-parallel scenario against both locking strategies, side by side. */
@SpringBootTest
class LockingStrategyComparisonTest {

    private static final int ATTEMPTS = 100;
    private static final int THREADS = 25;

    @Autowired TransferService pessimistic;
    @Autowired RetryingTransferService optimistic;
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
    private Account aliceEgp;
    private Account bobEgp;

    @BeforeEach
    void setUp() {
        alice = newUser("alice");
        AppUser bob = newUser("bob");
        aliceEgp = newAccount(alice, new BigDecimal("50.0000"));
        bobEgp = newAccount(bob, BigDecimal.ZERO);
        optimistic.resetRetryCount();
    }

    @AfterEach
    void tearDown() {
        testDataCleaner.deleteCreated(createdAccountIds, createdUserIds);
        createdAccountIds.clear();
        createdUserIds.clear();
    }

    @Test
    @DisplayName("PESSIMISTIC_WRITE: 50 succeed, no retries")
    void pessimisticStrategy() throws Exception {
        Result result = race((req, key) -> pessimistic.execute(req, key, alice.getPublicId()));
        report("PESSIMISTIC (select ... for update)", result, 0);
        assertOutcome(result);
    }

    @Test
    @DisplayName("@Version + retry: 50 succeed, at the cost of N retries")
    void optimisticStrategy() throws Exception {
        Result result = race((req, key) -> optimistic.execute(req, key, alice.getPublicId()));
        report("OPTIMISTIC (@Version + retry)", result, optimistic.retryCount());
        assertOutcome(result);
    }

    private void assertOutcome(Result result) {
        assertThat(result.succeeded).isEqualTo(50);
        assertThat(result.rejected).isEqualTo(50);
        assertThat(result.failed).isZero();
        assertThat(balanceOf(aliceEgp)).isEqualByComparingTo("0.0000");
        assertThat(balanceOf(bobEgp)).isEqualByComparingTo("50.0000");
        assertThat(myTransfers()).isEqualTo(50);
        assertThat(myLedgerEntries()).isEqualTo(100);
        assertThat(ledgerEntryRepository.balanceFromLedger(aliceEgp.getId()))
                .isEqualByComparingTo("-50.0000");
    }

    private Result race(BiConsumer<TransferRequest, String> strategy) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        for (int i = 0; i < ATTEMPTS; i++) {
            pool.submit(() -> {
                try {
                    gate.await();
                    strategy.accept(request(), UUID.randomUUID().toString());
                    succeeded.incrementAndGet();
                } catch (BusinessRuleException e) {
                    rejected.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                    System.out.println("        unexpected: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
                }
            });
        }

        long start = System.nanoTime();
        gate.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(180, TimeUnit.SECONDS)).isTrue();
        long millis = (System.nanoTime() - start) / 1_000_000;

        return new Result(succeeded.get(), rejected.get(), failed.get(), millis);
    }

    private void report(String label, Result r, long retries) {
        System.out.printf(">>>>> %-38s succeeded=%d rejected=%d failed=%d retries=%d wall=%dms%n",
                label, r.succeeded, r.rejected, r.failed, retries, r.millis);
    }

    private record Result(int succeeded, int rejected, int failed, long millis) {}

    private TransferRequest request() {
        return new TransferRequest(aliceEgp.getAccountNumber(),
                bobEgp.getAccountNumber(), new BigDecimal("1.0000"), "EGP", TransferType.P2P, null);
    }

    private AppUser newUser(String name) {
        AppUser user = new AppUser();
        user.setEmail(name + "-" + UUID.randomUUID() + "@example.com");
        user.setPhone("+3" + (System.nanoTime() % 1000000000000L));
        user.setFullName(name);
        user.setPasswordHash("x");
        user.addRole(roleRepository.findByName(Role.CUSTOMER).orElseThrow());
        AppUser saved = userRepository.saveAndFlush(user);
        createdUserIds.add(saved.getId());
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
        createdAccountIds.add(saved.getId());
        return saved;
    }

    private BigDecimal balanceOf(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
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
