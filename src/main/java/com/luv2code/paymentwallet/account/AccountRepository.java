package com.luv2code.paymentwallet.account;

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
           where a.type = com.luv2code.paymentwallet.account.AccountType.SYSTEM
             and a.currency.code = :currencyCode
           """)
    Optional<String> findSystemAccountNumber(String currencyCode);





    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(Long id);

    Optional<Account> findByAccountNumberAndUserPublicId(String accountNumber, UUID userPublicId);
}
