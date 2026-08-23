package com.moataz.paymentwallet.transfer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

public interface LedgerEntryRepository
        extends JpaRepository<LedgerEntry, Long>, JpaSpecificationExecutor<LedgerEntry> {
    @Override
    @EntityGraph(attributePaths = {"transfer", "transfer.sourceAccount", "transfer.destAccount"})
    Page<LedgerEntry> findAll(Specification<LedgerEntry> spec, Pageable pageable);

    List<LedgerEntry> findByTransferIdOrderById(Long transferId);

    void deleteByTransferIdIn(Collection<Long> transferIds);

    @Query("""
           select coalesce(sum(case when e.direction = com.moataz.paymentwallet.transfer.LedgerDirection.CREDIT
                                    then e.amount else -e.amount end), 0)
           from LedgerEntry e
           where e.account.id = :accountId
           """)
    BigDecimal balanceFromLedger(Long accountId);
}
