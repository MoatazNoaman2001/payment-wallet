package com.luv2code.paymentwallet.transfer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByTransferIdOrderById(Long transferId);

    /** Rebuilds a balance from the ledger. Used by the reconciliation test. */
    @Query("""
           select coalesce(sum(case when e.direction = com.luv2code.paymentwallet.transfer.LedgerDirection.CREDIT
                                    then e.amount else -e.amount end), 0)
           from LedgerEntry e
           where e.account.id = :accountId
           """)
    BigDecimal balanceFromLedger(Long accountId);
}
