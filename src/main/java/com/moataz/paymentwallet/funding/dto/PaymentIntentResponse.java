package com.moataz.paymentwallet.funding.dto;

import com.moataz.paymentwallet.funding.PaymentDirection;
import com.moataz.paymentwallet.funding.PaymentIntent;
import com.moataz.paymentwallet.funding.PaymentIntentStatus;
import com.moataz.paymentwallet.funding.PaymentProvider;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PaymentIntentResponse(
        String reference,
        PaymentDirection direction,
        PaymentProvider provider,
        String method,
        BigDecimal amount,
        String currencyCode,
        String accountNumber,
        PaymentIntentStatus status,
        String providerReference,
        String redirectUrl,
        String depositAddress,
        String transferReference,
        String failureReason,
        OffsetDateTime createdAt,
        OffsetDateTime confirmedAt
) {
    public static PaymentIntentResponse from(PaymentIntent intent) {
        return new PaymentIntentResponse(
                intent.getReference(),
                intent.getDirection(),
                intent.getProvider(),
                intent.getMethod(),
                intent.getAmount(),
                intent.getCurrency().getCode(),
                intent.getAccount().getAccountNumber(),
                intent.getStatus(),
                intent.getProviderReference(),
                intent.getRedirectUrl(),
                intent.getDepositAddress(),
                intent.getTransfer() == null ? null : intent.getTransfer().getReference(),
                intent.getFailureReason(),
                intent.getCreatedAt(),
                intent.getConfirmedAt());
    }
}
