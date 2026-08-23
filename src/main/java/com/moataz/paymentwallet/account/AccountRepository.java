package com.moataz.paymentwallet.account;

import com.moataz.paymentwallet.reliability.BalanceDriftView;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    /**
     * join fetch pulls the owner and the currency in the SAME query.
     * Without it, rendering 20 accounts costs 1 + 20 + 20 queries — and with
     * open-in-view: false you would not even get that far: you would get a
     * LazyInitializationException while serialising. Fetch what you need up front.
     */
    @Query("""
           select a from Account a
             join fetch a.user u
             join fetch a.currency
           where u.publicId = :ownerPublicId
           order by a.id
           """)
    List<Account> findAllByOwner(UUID ownerPublicId);

    /** Returns the id only, so the row does not enter the persistence context unlocked. */
    @Query("select a.id from Account a where a.accountNumber = :accountNumber")
    Optional<Long> findIdByAccountNumber(String accountNumber);

    @Query("select a.currency.code from Account a where a.accountNumber = :accountNumber")
    Optional<String> findCurrencyCodeByAccountNumber(String accountNumber);

    @Query("select a.type from Account a where a.accountNumber = :accountNumber")
    Optional<AccountType> findTypeByAccountNumber(String accountNumber);

    @Query("""
           select a.accountNumber from Account a
           where a.type = com.moataz.paymentwallet.account.AccountType.SYSTEM
             and a.currency.code = :currencyCode
           """)
    Optional<String> findSystemAccountNumber(String currencyCode);





    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(Long id);

    Optional<Account> findByAccountNumberAndUserPublicId(String accountNumber, UUID userPublicId);

    /**
     * Every account whose cached balance disagrees with the sum of its ledger entries.
     * Native because the aggregate + HAVING reads better in SQL, and aliases are quoted
     * so Postgres does not fold them to lower case.
     */
    @Query(value = """
           select a.account_number as "accountNumber",
                  a.balance        as "balance",
                  coalesce(sum(case when l.direction = 'CREDIT' then l.amount else -l.amount end), 0)
                                   as "ledgerBalance"
           from account a
           left join ledger_entry l on l.account_id = a.id
           group by a.id, a.account_number, a.balance
           having a.balance <> coalesce(
                  sum(case when l.direction = 'CREDIT' then l.amount else -l.amount end), 0)
           order by a.id
           """, nativeQuery = true)
    List<BalanceDriftView> findBalanceDrift();

    @Query("""
           select count(t) > 0 from Transfer t
           where t.reference = :reference
             and (t.sourceAccount.user.publicId = :ownerPublicId
                  or t.destAccount.user.publicId = :ownerPublicId)
           """)
    boolean existsByTransferReferenceAndOwner(String reference, UUID ownerPublicId);
}
