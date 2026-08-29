package com.moataz.paymentwallet.funding;

import com.moataz.paymentwallet.funding.provider.ProviderEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * The record of every webhook delivery, kept in its own transactions so it survives whatever
 * happens to the processing that follows.
 *
 * This is a separate bean rather than a few methods on WebhookService for a reason that costs
 * people hours: Spring's transaction management is a proxy, and a call from one method of a
 * bean to another goes straight down the inside of the object. REQUIRES_NEW on a method the
 * same class calls on itself does exactly nothing.
 */
@Service
@RequiredArgsConstructor
public class WebhookLog {

    private final WebhookEventRepository webhookEventRepository;

    /**
     * @return the row to process, or null when this delivery has already been dealt with. The
     *         unique index is what makes this safe when two copies arrive at once: the loser of
     *         that race gets a constraint violation, not a second posting.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long record(PaymentProvider provider, ProviderEvent event, String rawBody) {
        var existing = webhookEventRepository
                .findByProviderAndProviderEventId(provider, event.eventId());
        if (existing.isPresent()) {
            return existing.get().getProcessedAt() == null ? existing.get().getId() : null;
        }

        WebhookEvent record = new WebhookEvent();
        record.setProvider(provider);
        record.setProviderEventId(event.eventId());
        record.setEventType(event.eventType());
        record.setPayload(rawBody);
        record.setSignatureVerified(true);
        try {
            return webhookEventRepository.saveAndFlush(record).getId();
        } catch (DataIntegrityViolationException ex) {
            return null;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessed(Long id) {
        webhookEventRepository.findById(id).ifPresent(record ->
                record.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC)));
    }

    /** Deliberately leaves processed_at null, so the provider's next retry tries again. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long id, String error) {
        webhookEventRepository.findById(id).ifPresent(record ->
                record.setError(error == null || error.length() <= 500
                        ? error
                        : error.substring(0, 500)));
    }
}
