package com.moataz.paymentwallet.funding;

import com.moataz.paymentwallet.funding.provider.ProviderEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Turning a provider's "at least once, in no particular order" into "effectively once".
 *
 * Providers retry a webhook until they get a 2xx, so the same event will arrive again: after a
 * timeout, after a deploy, after somebody replays it from a dashboard. Three things make that
 * safe. The signature must verify. The delivery is recorded under a unique provider event id.
 * And the intent itself refuses to leave a terminal state, so even a replay that slips past the
 * first two guards credits nobody twice.
 */
@Service
@RequiredArgsConstructor
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final ProviderRegistry registry;
    private final WebhookLog webhookLog;
    private final FundingLedger ledger;

    public String handle(PaymentProvider provider, String rawBody, Map<String, String> headers) {
        // verification first: an unsigned webhook is an anonymous stranger claiming money arrived
        ProviderEvent event = registry.get(provider).readEvent(rawBody, headers);

        Long id = webhookLog.record(provider, event, rawBody);
        if (id == null) {
            log.info("{} event {} already processed, ignoring the replay", provider, event.eventId());
            return "duplicate";
        }

        try {
            if (event.outcome() == null) {
                // understood, but it moves no money: a crypto payment short of its confirmations,
                // or a PayPal approval that has not been captured yet
                webhookLog.markProcessed(id);
                return "acknowledged";
            }

            ledger.applyOutcome(provider, event.providerReference(),
                                event.outcome(), event.failureReason());
            webhookLog.markProcessed(id);
            return "processed";
        } catch (RuntimeException ex) {
            log.error("{} event {} failed: {}", provider, event.eventId(), ex.getMessage());
            webhookLog.markFailed(id, ex.getMessage());
            throw ex;
        }
    }
}
