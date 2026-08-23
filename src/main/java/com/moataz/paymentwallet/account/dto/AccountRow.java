package com.moataz.paymentwallet.account.dto;

import com.moataz.paymentwallet.account.Account;
import com.moataz.paymentwallet.account.AccountStatus;
import com.moataz.paymentwallet.account.AccountType;

import java.math.BigDecimal;

public record AccountRow(
        String accountNumber,
        String ownerEmail,
        String ownerName,
        String currencyCode,
        AccountType type,
        AccountStatus status,
        BigDecimal balance,
        BigDecimal dailyLimit
) {
    public static AccountRow from(Account account) {
        return new AccountRow(
                account.getAccountNumber(),
                account.getUser().getEmail(),
                account.getUser().getFullName(),
                account.getCurrency().getCode(),
                account.getType(),
                account.getStatus(),
                account.getBalance(),
                account.getDailyLimit());
    }
}
