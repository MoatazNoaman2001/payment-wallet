package com.moataz.paymentwallet.reliability;

import com.moataz.paymentwallet.transfer.OutboxEvent;
import com.moataz.paymentwallet.transfer.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;

    @Transactional
    public int publishBatch(int batchSize) {
        List<OutboxEvent> batch = outboxEventRepository.claimUnpublished(batchSize);
        if (batch.isEmpty()) {
            return 0;
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (OutboxEvent event : batch) {
            publish(event);
            event.setPublishedAt(now);
        }
        log.info("Outbox: published {} event(s), {} still pending",
                 batch.size(), outboxEventRepository.countByPublishedAtIsNull() - batch.size());
        return batch.size();
    }

    private void publish(OutboxEvent event) {
        log.info("Outbox -> {} {} {}", event.getEventType(), event.getAggregateId(), event.getPayload());
    }
}
