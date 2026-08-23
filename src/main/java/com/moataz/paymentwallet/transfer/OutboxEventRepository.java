package com.moataz.paymentwallet.transfer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByAggregateIdOrderById(String aggregateId);

    void deleteByAggregateIdIn(Collection<String> aggregateIds);

    /**
     * Claims a batch of unpublished events for this worker.
     *
     * FOR UPDATE SKIP LOCKED is what makes this safe to run on more than one instance:
     * rows already locked by another worker are skipped rather than waited for, so N
     * publishers take disjoint batches and never block each other. This is the standard
     * "queue in a database" pattern, and it needs native SQL because JPQL has no way to
     * express SKIP LOCKED.
     */
    @Query(value = """
           select * from outbox_event
           where published_at is null
           order by created_at
           limit :batchSize
           for update skip locked
           """, nativeQuery = true)
    List<OutboxEvent> claimUnpublished(int batchSize);

    long countByPublishedAtIsNull();
}
