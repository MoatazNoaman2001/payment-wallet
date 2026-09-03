package com.moataz.paymentwallet.funding.provider;

import com.moataz.paymentwallet.funding.PaymentDirection;
import com.moataz.paymentwallet.funding.PaymentProvider;

import java.util.Map;
import java.util.Set;

/**
 * The seam between this wallet and somebody else's money movement.
 *
 * Everything above this interface is the same for Stripe, PayPal and a blockchain: an intent
 * is recorded, the customer is sent somewhere, and the ledger is only touched when the
 * provider says the money is real. Everything provider-specific lives below it.
 */
public interface PaymentProviderAdapter {

    PaymentProvider provider();

    /**
     * False until the deployment has credentials. Providers are discovered at startup rather
     * than assumed, so a wallet with no Stripe key refuses Stripe clearly instead of failing
     * somewhere deep in an HTTP call.
     */
    boolean isConfigured();

    Set<PaymentDirection> supports();

    /** Ask the provider to collect money from the customer. */
    ProviderHandle createCharge(PaymentContext payment);

    /** Ask the provider to send money to the customer. */
    ProviderHandle createPayout(PaymentContext payment);

    /**
     * Verify the signature and reduce the payload to a {@link ProviderEvent}.
     *
     * @throws WebhookVerificationException if the signature is missing, stale or wrong. An
     *         unverified webhook is an anonymous stranger claiming money arrived.
     */
    ProviderEvent readEvent(String rawBody, Map<String, String> headers);

    /**
     * Some providers authorise and capture in two steps, and an approval on its own moves no
     * money. Called for any event this wallet understands but which settles nothing, so the
     * adapter can take the second step; the provider then reports the capture as its own event.
     */
    default void captureIfNeeded(ProviderEvent event) {
    }

    default boolean supports(PaymentDirection direction) {
        return supports().contains(direction);
    }
}
