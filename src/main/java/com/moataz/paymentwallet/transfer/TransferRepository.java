package com.moataz.paymentwallet.transfer;

import com.moataz.paymentwallet.statement.TagSpendRow;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
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
             and t.status = com.moataz.paymentwallet.transfer.TransferStatus.POSTED
             and t.createdAt >= :since
           """)
    BigDecimal sumPostedSince(Long accountId, OffsetDateTime since);

    /**
     * A constructor expression: the query selects three scalars straight into a record.
     * No Transfer or Tag entity is ever materialised, nothing enters the persistence
     * context, and there is nothing to dirty-check.
     */
    @Query("""
           select new com.moataz.paymentwallet.statement.TagSpendRow(
                      tg.name, sum(t.amount), count(t))
           from Transfer t
             join t.tags tg
           where t.sourceAccount.accountNumber = :accountNumber
             and t.status = com.moataz.paymentwallet.transfer.TransferStatus.POSTED
             and t.createdAt >= :from
             and t.createdAt < :to
           group by tg.name
           order by sum(t.amount) desc
           """)
    List<TagSpendRow> spendByTag(String accountNumber, OffsetDateTime from, OffsetDateTime to);

    @EntityGraph(attributePaths = "tags")
    Optional<Transfer> findWithTagsByReference(String reference);

    /** The compensating transfer written for an original, if it has been reversed. */
    @Query("""
           select t from Transfer t
             join fetch t.sourceAccount
             join fetch t.destAccount
             join fetch t.currency
           where t.reversesTransfer.reference = :originalReference
           """)
    Optional<Transfer> findReversalOf(String originalReference);

    /** Every transfer that touches any of these accounts, on either side. */
    List<Transfer> findBySourceAccountIdInOrDestAccountIdIn(Collection<Long> source,
                                                            Collection<Long> dest);
}
