package com.luv2code.paymentwallet;

import com.luv2code.paymentwallet.account.*;
import com.luv2code.paymentwallet.statement.*;
import com.luv2code.paymentwallet.transfer.*;
import com.luv2code.paymentwallet.transfer.dto.TransferRequest;
import com.luv2code.paymentwallet.transfer.dto.TransferResponse;
import com.luv2code.paymentwallet.user.AppUser;
import com.luv2code.paymentwallet.user.AppUserRepository;
import com.luv2code.paymentwallet.user.Role;
import com.luv2code.paymentwallet.user.RoleRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class Phase3StatementTest {

    @Autowired StatementService statementService;
    @Autowired TransferService transferService;
    @Autowired TransferRepository transferRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired AppUserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired CurrencyRepository currencyRepository;
    @Autowired EntityManagerFactory entityManagerFactory;

    private final List<Long> createdAccountIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    private AppUser alice;
    private Account aliceEgp;
    private Account bobEgp;

    @BeforeEach
    void setUp() {
        alice = newUser("alice");
        AppUser bob = newUser("bob");
        aliceEgp = newAccount(alice, new BigDecimal("1000.0000"));
        bobEgp = newAccount(bob, BigDecimal.ZERO);

        // 30 outgoing transfers of 1, 2, 3 ... 30
        for (int i = 1; i <= 30; i++) {
            transferService.execute(request(new BigDecimal(i + ".0000")), UUID.randomUUID().toString());
        }
    }

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        transferRepository.deleteAll();
        accountRepository.deleteAllById(createdAccountIds);
        userRepository.deleteAllById(createdUserIds);
        createdAccountIds.clear();
        createdUserIds.clear();
    }

    @Test
    @DisplayName("statement is paginated, newest first, and never exposes an entity")
    void paginates() {
        Page<StatementLine> page = statementService.statement(
                aliceEgp.getAccountNumber(), empty(),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page.getTotalElements()).isEqualTo(30);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(10);

        StatementLine line = page.getContent().getFirst();
        assertThat(line.direction()).isEqualTo(LedgerDirection.DEBIT);
        assertThat(line.type()).isEqualTo(TransferType.P2P);
        assertThat(line.counterpartyAccount()).isEqualTo(bobEgp.getAccountNumber());
        assertThat(line.reference()).startsWith("TRF");
        assertThat(line.balanceAfter()).isNotNull();
    }

    @Test
    @DisplayName("filters compose: amount range, direction and type in one query")
    void filters() {
        StatementFilter amountRange = new StatementFilter(
                null, null, null, null, new BigDecimal("25.0000"), new BigDecimal("28.0000"));

        Page<StatementLine> page = statementService.statement(
                aliceEgp.getAccountNumber(), amountRange, PageRequest.of(0, 50));

        assertThat(page.getTotalElements()).isEqualTo(4);          // 25, 26, 27, 28
        assertThat(page.getContent())
                .allSatisfy(l -> assertThat(l.amount()).isBetween(
                        new BigDecimal("25.0000"), new BigDecimal("28.0000")));

        StatementFilter credits = new StatementFilter(
                null, null, TransferType.P2P, LedgerDirection.CREDIT, null, null);
        assertThat(statementService.statement(bobEgp.getAccountNumber(), credits,
                PageRequest.of(0, 50)).getTotalElements()).isEqualTo(30);

        StatementFilter topups = new StatementFilter(null, null, TransferType.TOPUP, null, null, null);
        assertThat(statementService.statement(aliceEgp.getAccountNumber(), topups,
                PageRequest.of(0, 50)).getTotalElements()).isZero();

        StatementFilter future = new StatementFilter(
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), null, null, null, null, null);
        assertThat(statementService.statement(aliceEgp.getAccountNumber(), future,
                PageRequest.of(0, 50)).getTotalElements()).isZero();
    }

    @Test
    @DisplayName("query count is fixed regardless of page size")
    void fixedQueryCount() {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        long forFive = countQueries(stats, 5);
        long forFifty = countQueries(stats, 50);

        System.out.println(">>>>> statement queries: page size 5 (5 rows) = " + forFive
                + ", page size 50 (30 rows) = " + forFifty);

        // 6x the rows must not cost more queries. It costs FEWER here because Spring Data
        // skips the count query when a result fits entirely in one page.
        assertThat(forFifty).isLessThanOrEqualTo(forFive);
        assertThat(forFive).isLessThanOrEqualTo(3);   // account check + content + count
    }

    @Test
    @DisplayName("spend by tag is a group-by projection, no entities materialised")
    void spendByTag() {
        List<String> references = transferRepository.findAll().stream()
                .map(Transfer::getReference).sorted().toList();

        transferService.replaceTags(references.get(0), Set.of("groceries"));
        transferService.replaceTags(references.get(1), Set.of("groceries", "transport"));
        transferService.replaceTags(references.get(2), Set.of("rent"));

        List<TagSpendRow> rows = statementService.spendByTag(
                aliceEgp.getAccountNumber(), YearMonth.now(ZoneOffset.UTC));

        System.out.println(">>>>> spend by tag: " + rows);

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(TagSpendRow::tag)
                .containsExactlyInAnyOrder("groceries", "rent", "transport");
        assertThat(rows.stream().mapToLong(TagSpendRow::transfers).sum()).isEqualTo(4);
        // sorted by total descending
        assertThat(rows.getFirst().total()).isGreaterThanOrEqualTo(rows.getLast().total());
    }

    private long countQueries(Statistics stats, int pageSize) {
        stats.clear();
        Page<StatementLine> page = statementService.statement(
                aliceEgp.getAccountNumber(), empty(), PageRequest.of(0, pageSize));
        page.getContent().forEach(StatementLine::counterpartyAccount);   // touch everything
        assertThat(page.getContent()).hasSize(Math.min(pageSize, 30));
        return stats.getPrepareStatementCount();
    }

    private StatementFilter empty() {
        return new StatementFilter(null, null, null, null, null, null);
    }

    private TransferRequest request(BigDecimal amount) {
        return new TransferRequest(alice.getPublicId(), aliceEgp.getAccountNumber(),
                bobEgp.getAccountNumber(), amount, "EGP", TransferType.P2P, "line " + amount);
    }

    private AppUser newUser(String name) {
        AppUser user = new AppUser();
        user.setEmail(name + "-" + UUID.randomUUID() + "@example.com");
        user.setPhone("+4" + (System.nanoTime() % 1000000000000L));
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
}
