package com.moataz.paymentwallet.statement;

import com.moataz.paymentwallet.transfer.LedgerDirection;
import com.moataz.paymentwallet.transfer.LedgerEntry;
import com.moataz.paymentwallet.transfer.TransferStatus;
import com.moataz.paymentwallet.transfer.TransferType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record StatementLine(
        OffsetDateTime date,
        String reference,
        TransferType type,
        TransferStatus status,
        LedgerDirection direction,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String counterpartyAccount,
        String description
) {
    /** A reversal cannot be reversed, and neither can a transfer that already has been. */
    public boolean isReversible() {
        return status == TransferStatus.POSTED && type != TransferType.REVERSAL;
    }

    static StatementLine from(LedgerEntry entry) {
        var transfer = entry.getTransfer();
        String counterparty = entry.getDirection() == LedgerDirection.DEBIT
                ? transfer.getDestAccount().getAccountNumber()
                : transfer.getSourceAccount().getAccountNumber();

        return new StatementLine(
                entry.getCreatedAt(),
                transfer.getReference(),
                transfer.getType(),
                transfer.getStatus(),
                entry.getDirection(),
                entry.getAmount(),
                entry.getBalanceAfter(),
                counterparty,
                transfer.getDescription());
    }
}
