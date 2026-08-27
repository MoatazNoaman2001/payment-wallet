package com.moataz.paymentwallet.account;

import com.moataz.paymentwallet.reliability.BalanceDriftView;
import com.moataz.paymentwallet.user.AppUser;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    @Query("""
           select a from Account a
             join fetch a.user u
             join fetch a.currency
           where u.publicId = :ownerPublicId
           order by a.id
           """)
    List<Account> findAllByOwner(UUID ownerPublicId);

    @Query(value = """
           select a from Account a
             join fetch a.user
             join fetch a.currency
           where a.user.publicId = :ownerPublicId
           """,
           countQuery = "select count(a) from Account a where a.user.publicId = :ownerPublicId")
    Page<Account> findPageByOwner(UUID ownerPublicId, Pageable pageable);

    @Query(value = """
           select a from Account a
             join fetch a.user
             join fetch a.currency
           """,
           countQuery = "select count(a) from Account a")
    Page<Account> findPageWithOwner(Pageable pageable);

    @Query(value = """
           select distinct u from AppUser u
           where exists (select 1 from Account a
                         where a.user = u
                           and a.type <> com.moataz.paymentwallet.account.AccountType.SYSTEM)
           order by u.email
           """,
           countQuery = """
           select count(distinct u) from AppUser u
           where exists (select 1 from Account a
                         where a.user = u
                           and a.type <> com.moataz.paymentwallet.account.AccountType.SYSTEM)
           """)
    Page<AppUser> findCustomersWithAccounts(Pageable pageable);

    @Query("""
           select a from Account a
             join fetch a.user u
             join fetch a.currency
           where u.id in :ownerIds
             and a.type <> com.moataz.paymentwallet.account.AccountType.SYSTEM
           order by a.id
           """)
    List<Account> findCustomerAccountsFor(Collection<Long> ownerIds);

    @Query("""
           select a from Account a
             join fetch a.user
             join fetch a.currency
           where a.type = com.moataz.paymentwallet.account.AccountType.SYSTEM
           order by a.currency.code
           """)
    List<Account> findSettlementAccounts();

    @Query("""
           select a from Account a
             join fetch a.user
             join fetch a.currency
           where a.accountNumber = :accountNumber
           """)
    Optional<Account> findWithOwnerByAccountNumber(String accountNumber);

    long countByUserPublicId(UUID publicId);

    @Query("select a.id from Account a where a.user.id in :ownerIds")
    List<Long> findIdsByOwnerIds(Collection<Long> ownerIds);

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
