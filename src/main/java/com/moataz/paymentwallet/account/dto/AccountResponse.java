package com.moataz.paymentwallet.account.dto;

import com.moataz.paymentwallet.account.Account;
import com.moataz.paymentwallet.account.AccountStatus;
import com.moataz.paymentwallet.account.AccountType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AccountResponse(
        String accountNumber,
        UUID ownerPublicId,
        String currencyCode,
        AccountType type,
        AccountStatus status,
        BigDecimal balance,
        BigDecimal dailyLimit,
        OffsetDateTime createdAt
) {
    /** Touches user and currency, so it must be called while the session is open. */
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getAccountNumber(),
                account.getUser().getPublicId(),
                account.getCurrency().getCode(),
                account.getType(),
                account.getStatus(),
                account.getBalance(),
                account.getDailyLimit(),
                account.getCreatedAt());
    }
}
