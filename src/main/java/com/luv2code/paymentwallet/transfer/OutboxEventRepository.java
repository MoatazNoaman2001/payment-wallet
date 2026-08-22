package com.luv2code.paymentwallet.transfer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByAggregateIdOrderById(String aggregateId);

    void deleteByAggregateIdIn(Collection<String> aggregateIds);
}
