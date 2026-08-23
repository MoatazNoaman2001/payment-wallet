package com.moataz.paymentwallet.transfer.dto;

import com.moataz.paymentwallet.transfer.Transfer;
import com.moataz.paymentwallet.transfer.TransferStatus;
import com.moataz.paymentwallet.transfer.TransferType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TransferResponse(
        String reference,
        TransferStatus status,
        String sourceAccountNumber,
        String destAccountNumber,
        BigDecimal amount,
        BigDecimal fee,
        String currencyCode,
        TransferType type,
        String description,
        OffsetDateTime createdAt,
        OffsetDateTime postedAt
) {
    public static TransferResponse from(Transfer t) {
        return new TransferResponse(
                t.getReference(),
                t.getStatus(),
                t.getSourceAccount().getAccountNumber(),
                t.getDestAccount().getAccountNumber(),
                t.getAmount(),
                t.getFee(),
                t.getCurrency().getCode(),
                t.getType(),
                t.getDescription(),
                t.getCreatedAt(),
                t.getPostedAt());
    }
}
