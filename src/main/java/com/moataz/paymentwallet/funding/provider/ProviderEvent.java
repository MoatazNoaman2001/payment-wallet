package com.moataz.paymentwallet.funding.provider;

import com.moataz.paymentwallet.funding.PaymentIntentStatus;

/**
 * A provider webhook, reduced to the four things this wallet acts on. Everything else in
 * the payload is kept verbatim in webhook_event for the audit trail.
 */
public record ProviderEvent(
        String eventId,
        String eventType,
        String providerReference,
        PaymentIntentStatus outcome,
        String failureReason
) {
    /** An event we understand but which changes nothing, such as an unconfirmed crypto payment. */
    public boolean isTerminal() {
        return outcome != null && outcome.isTerminal();
    }
}
