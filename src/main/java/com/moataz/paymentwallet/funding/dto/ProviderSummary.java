package com.moataz.paymentwallet.funding.dto;

import com.moataz.paymentwallet.funding.PaymentDirection;
import com.moataz.paymentwallet.funding.PaymentProvider;

import java.util.Set;

public record ProviderSummary(
        PaymentProvider provider,
        boolean configured,
        Set<PaymentDirection> supports
) {
}
