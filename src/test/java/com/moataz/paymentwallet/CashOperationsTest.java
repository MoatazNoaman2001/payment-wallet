package com.moataz.paymentwallet;

import com.moataz.paymentwallet.account.*;
import com.moataz.paymentwallet.common.error.BusinessRuleException;
import com.moataz.paymentwallet.transfer.*;
import com.moataz.paymentwallet.transfer.dto.CashRequest;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CashOperationsTest {

    @Autowired CashService cashService;
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

    private java.math.BigDecimal settlementAtStart;
    private java.math.BigDecimal settlementLedgerAtStart;
    private AppUser alice;
    private Account wallet;

    @BeforeEach
    void setUp() {
        // the settlement account is shared with all other data in this database,
        // so assert on how much it moved, not on an absolute figure
        settlementAtStart = accountRepository.findByAccountNumber("SYSTEM-EGP").orElseThrow().getBalance();
        settlementLedgerAtStart = ledgerEntryRepository.balanceFromLedger(
                accountRepository.findByAccountNumber("SYSTEM-EGP").orElseThrow().getId());
        alice = newUser("alice");
        wallet = newAccount(alice);
    }

    @AfterEach
    void tearDown() {
        testDataCleaner.deleteCreated(createdAccountIds, createdUserIds);
        createdAccountIds.clear();
        createdUserIds.clear();
    }

    @Test
    @DisplayName("a deposit credits the wallet and debits the settlement account")
    void deposits() {
        TransferResponse response = cashService.deposit(wallet.getAccountNumber(), alice.getPublicId(),
                new CashRequest(new BigDecimal("250.0000"), "salary"), UUID.randomUUID().toString());

        assertThat(response.type()).isEqualTo(TransferType.TOPUP);
        assertThat(response.status()).isEqualTo(TransferStatus.POSTED);
        assertThat(response.sourceAccountNumber()).isEqualTo("SYSTEM-EGP");

        assertThat(balanceOf(wallet)).isEqualByComparingTo("250.0000");
        assertThat(settlementMoved()).isEqualByComparingTo("-250.0000");

        assertThat(ledgerEntryRepository.balanceFromLedger(wallet.getId()))
                .isEqualByComparingTo("250.0000");
        assertThat(settlementLedgerMoved()).isEqualByComparingTo("-250.0000");
    }

    @Test
    @DisplayName("a withdrawal moves money back out and reconciles")
    void withdraws() {
        cashService.deposit(wallet.getAccountNumber(), alice.getPublicId(),
                new CashRequest(new BigDecimal("100.0000"), null), UUID.randomUUID().toString());

        TransferResponse response = cashService.withdraw(wallet.getAccountNumber(), alice.getPublicId(),
                new CashRequest(new BigDecimal("30.0000"), "atm"), UUID.randomUUID().toString());

        assertThat(response.type()).isEqualTo(TransferType.WITHDRAWAL);
        assertThat(response.destAccountNumber()).isEqualTo("SYSTEM-EGP");

        assertThat(balanceOf(wallet)).isEqualByComparingTo("70.0000");
        assertThat(settlementMoved()).isEqualByComparingTo("-70.0000");
        assertThat(ledgerEntryRepository.balanceFromLedger(wallet.getId()))
                .isEqualByComparingTo(balanceOf(wallet));
        assertThat(myLedgerEntries()).isEqualTo(4);
    }

    @Test
    @DisplayName("a wallet cannot be overdrawn, but the settlement account can")
    void rejectsOverdraft() {
        assertThatThrownBy(() -> cashService.withdraw(wallet.getAccountNumber(), alice.getPublicId(),
                new CashRequest(new BigDecimal("1.0000"), null), UUID.randomUUID().toString()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Insufficient funds");

        cashService.deposit(wallet.getAccountNumber(), alice.getPublicId(),
                new CashRequest(new BigDecimal("5000.0000"), null), UUID.randomUUID().toString());
        assertThat(settlementMoved()).isEqualByComparingTo("-5000.0000");
    }

    @Test
    @DisplayName("deposits are idempotent like any other transfer")
    void depositIsIdempotent() {
        String key = UUID.randomUUID().toString();
        CashRequest request = new CashRequest(new BigDecimal("40.0000"), null);

        TransferResponse first = cashService.deposit(wallet.getAccountNumber(), alice.getPublicId(), request, key);
        TransferResponse retry = cashService.deposit(wallet.getAccountNumber(), alice.getPublicId(), request, key);

        assertThat(retry.reference()).isEqualTo(first.reference());
        assertThat(balanceOf(wallet)).isEqualByComparingTo("40.0000");
        assertThat(myTransfers()).isEqualTo(1);
    }

    @Test
    @DisplayName("targeting the settlement account itself is rejected with a clear message")
    void rejectsSettlementAccountAsTarget() {
        assertThatThrownBy(() -> cashService.deposit("SYSTEM-EGP", alice.getPublicId(),
                new CashRequest(new BigDecimal("50000.0000"), "ATM DEPOSIT"), "DEP-101"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Cannot deposit into settlement account SYSTEM-EGP");

        assertThatThrownBy(() -> cashService.withdraw("SYSTEM-EGP", alice.getPublicId(),
                new CashRequest(new BigDecimal("10.0000"), null), "WDR-101"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Cannot withdraw from settlement account SYSTEM-EGP");

        assertThat(settlementMoved()).isEqualByComparingTo("0.0000");
    }

    private java.math.BigDecimal settlementLedgerMoved() {
        return ledgerEntryRepository.balanceFromLedger(settlement().getId())
                .subtract(settlementLedgerAtStart);
    }

    /** How far the shared settlement account moved during this test. */
    private java.math.BigDecimal settlementMoved() {
        return settlement().getBalance().subtract(settlementAtStart);
    }

    private Account settlement() {
        return accountRepository.findByAccountNumber("SYSTEM-EGP").orElseThrow();
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

    private Account newAccount(AppUser owner) {
        Account account = new Account();
        account.setAccountNumber("PW" + String.format("%016d", System.nanoTime() % 10000000000000000L));
        account.setUser(owner);
        account.setCurrency(currencyRepository.findById("EGP").orElseThrow());
        account.setType(AccountType.WALLET);
        account.setStatus(AccountStatus.ACTIVE);
        account.setBalance(BigDecimal.ZERO);
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
