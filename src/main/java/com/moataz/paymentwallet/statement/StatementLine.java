package com.moataz.paymentwallet.statement;

import com.moataz.paymentwallet.transfer.LedgerDirection;
import com.moataz.paymentwallet.transfer.LedgerEntry;
import com.moataz.paymentwallet.transfer.TransferType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One row of an account statement: a single ledger leg, from that account's side. */
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
        // the other side of the transfer, seen from this account
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
