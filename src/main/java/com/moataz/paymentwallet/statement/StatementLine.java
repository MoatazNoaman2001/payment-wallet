package com.moataz.paymentwallet.statement;

import com.moataz.paymentwallet.transfer.LedgerDirection;
import com.moataz.paymentwallet.transfer.LedgerEntry;
import com.moataz.paymentwallet.transfer.TransferType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record StatementLine(
        OffsetDateTime date,
        String reference,
        TransferType type,
        LedgerDirection direction,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String counterpartyAccount,
        String description
) {
    static StatementLine from(LedgerEntry entry) {
        var transfer = entry.getTransfer();
        String counterparty = entry.getDirection() == LedgerDirection.DEBIT
                ? transfer.getDestAccount().getAccountNumber()
                : transfer.getSourceAccount().getAccountNumber();

        return new StatementLine(
                entry.getCreatedAt(),
                transfer.getReference(),
                transfer.getType(),
                entry.getDirection(),
                entry.getAmount(),
                entry.getBalanceAfter(),
                counterparty,
                transfer.getDescription());
    }
}
