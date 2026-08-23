package com.moataz.paymentwallet.reliability;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "reliability.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ReliabilityScheduler {
    private final ReconciliationService reconciliationService;
    private final OutboxPublisher outboxPublisher;

    @Scheduled(fixedDelayString = "${reliability.outbox.interval-ms:5000}", initialDelay = 10_000)
    public void publishOutbox() {
        outboxPublisher.publishBatch(100);
    }

    @Scheduled(fixedDelayString = "${reliability.reconciliation.interval-ms:300000}", initialDelay = 30_000)
    public void reconcile() {
        reconciliationService.report();
    }
}
