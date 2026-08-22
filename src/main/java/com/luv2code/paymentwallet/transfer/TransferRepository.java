package com.luv2code.paymentwallet.transfer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    @Query("""
           select t from Transfer t
             join fetch t.sourceAccount
             join fetch t.destAccount
             join fetch t.currency
           where t.initiatedBy.publicId = :initiatorPublicId
             and t.idempotencyKey = :key
           """)
    Optional<Transfer> findByInitiatorAndKey(UUID initiatorPublicId, String key);

    @Query("""
           select t from Transfer t
             join fetch t.sourceAccount
             join fetch t.destAccount
             join fetch t.currency
           where t.reference = :reference
           """)
    Optional<Transfer> findByReference(String reference);

    boolean existsByReference(String reference);

    @Query("""
           select coalesce(sum(t.amount), 0) from Transfer t
           where t.sourceAccount.id = :accountId
             and t.status = com.luv2code.paymentwallet.transfer.TransferStatus.POSTED
             and t.createdAt >= :since
           """)
    BigDecimal sumPostedSince(Long accountId, OffsetDateTime since);
}
