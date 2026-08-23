package com.moataz.paymentwallet.transfer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findByAggregateIdOrderById(String aggregateId);

    void deleteByAggregateIdIn(Collection<String> aggregateIds);

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
