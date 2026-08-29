package com.moataz.paymentwallet.funding.provider;

import com.moataz.paymentwallet.funding.PaymentDirection;
import com.moataz.paymentwallet.funding.PaymentIntent;
import com.moataz.paymentwallet.funding.PaymentProvider;

import java.math.BigDecimal;

/**
 * Everything an adapter needs about a payment, and nothing else.
 *
 * Deliberately not the JPA entity. The provider call happens outside any transaction, so an
 * entity handed across that boundary is detached and every lazy association on it is a
 * LazyInitializationException waiting to happen. A record cannot fail that way, and it also
 * stops an adapter from quietly writing to the database.
 */
public record PaymentContext(
        String reference,
        PaymentProvider provider,
        PaymentDirection direction,
        BigDecimal amount,
        String currencyCode,
        int minorUnits,
        String customerEmail,
        String method
) {
    public static PaymentContext of(PaymentIntent intent) {
        return new PaymentContext(
                intent.getReference(),
                intent.getProvider(),
                intent.getDirection(),
                intent.getAmount(),
                intent.getCurrency().getCode(),
                intent.getCurrency().getMinorUnits(),
                intent.getUser().getEmail(),
                intent.getMethod());
    }
}
