package com.luv2code.paymentwallet.transfer.dto;

import com.luv2code.paymentwallet.transfer.Transfer;
import com.luv2code.paymentwallet.transfer.TransferStatus;
import com.luv2code.paymentwallet.transfer.TransferType;

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
