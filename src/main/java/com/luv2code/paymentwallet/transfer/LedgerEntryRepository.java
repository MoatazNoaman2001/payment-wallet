package com.luv2code.paymentwallet.transfer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.List;

public interface LedgerEntryRepository
        extends JpaRepository<LedgerEntry, Long>, JpaSpecificationExecutor<LedgerEntry> {

    /**
     * The @EntityGraph is what keeps the statement at a fixed query count: transfer and
     * both of its accounts arrive in the same SELECT, so building each StatementLine
     * touches no lazy proxy. All three are @ManyToOne, so this stays a plain join and
     * Postgres still applies LIMIT/OFFSET - unlike fetching a collection, which would
     * force Hibernate to page in memory (HHH000104).
     */
    @Override
    @EntityGraph(attributePaths = {"transfer", "transfer.sourceAccount", "transfer.destAccount"})
    Page<LedgerEntry> findAll(Specification<LedgerEntry> spec, Pageable pageable);

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
