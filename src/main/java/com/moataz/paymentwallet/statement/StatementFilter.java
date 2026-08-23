package com.moataz.paymentwallet.statement;

import com.moataz.paymentwallet.transfer.LedgerDirection;
import com.moataz.paymentwallet.transfer.TransferType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Every field optional. Null means "do not filter on this". */
public record StatementFilter(
        OffsetDateTime from,
        OffsetDateTime to,
        TransferType type,
        LedgerDirection direction,
        BigDecimal minAmount,
        BigDecimal maxAmount
) {
}
