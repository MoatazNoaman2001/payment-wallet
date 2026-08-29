package com.moataz.paymentwallet.funding.provider;

import com.moataz.paymentwallet.funding.PaymentIntentStatus;

/**
 * What the provider gave back when we asked it to collect or send money. The reference is
 * theirs, and it is the only thing a later webhook will use to identify the payment.
 */
public record ProviderHandle(
        String providerReference,
        PaymentIntentStatus status,
        String redirectUrl,
        String depositAddress
) {
    public static ProviderHandle redirect(String reference, String url) {
        return new ProviderHandle(reference, PaymentIntentStatus.REQUIRES_ACTION, url, null);
    }

    public static ProviderHandle address(String reference, String depositAddress) {
        return new ProviderHandle(reference, PaymentIntentStatus.REQUIRES_ACTION, null, depositAddress);
    }

    public static ProviderHandle pending(String reference) {
        return new ProviderHandle(reference, PaymentIntentStatus.PENDING, null, null);
    }
}
