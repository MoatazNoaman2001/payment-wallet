package com.luv2code.paymentwallet.transfer;

import com.luv2code.paymentwallet.account.AccountRepository;
import com.luv2code.paymentwallet.account.AccountType;
import com.luv2code.paymentwallet.common.error.BusinessRuleException;
import com.luv2code.paymentwallet.common.error.NotFoundException;
import com.luv2code.paymentwallet.transfer.dto.CashRequest;
import com.luv2code.paymentwallet.transfer.dto.TransferRequest;
import com.luv2code.paymentwallet.transfer.dto.TransferResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Deposits and withdrawals are ordinary transfers against the SYSTEM settlement
 * account for the wallet's currency, so they inherit idempotency, locking, the
 * double-entry legs and the outbox event for free.
 */
@Service
@RequiredArgsConstructor
public class CashService {

    private final TransferService transferService;
    private final AccountRepository accountRepository;

    public TransferResponse deposit(String accountNumber, UUID initiatorPublicId,
                                    CashRequest request, String idempotencyKey) {
        requireWallet(accountNumber, "deposit into");
        String currency = currencyOf(accountNumber);
        return transferService.execute(new TransferRequest(
                initiatorPublicId,
                systemAccountFor(currency),
                accountNumber,
                request.amount(),
                currency,
                TransferType.TOPUP,
                request.description()), idempotencyKey);
    }

    public TransferResponse withdraw(String accountNumber, UUID initiatorPublicId,
                                     CashRequest request, String idempotencyKey) {
        requireWallet(accountNumber, "withdraw from");
        String currency = currencyOf(accountNumber);
        return transferService.execute(new TransferRequest(
                initiatorPublicId,
                accountNumber,
                systemAccountFor(currency),
                request.amount(),
                currency,
                TransferType.WITHDRAWAL,
                request.description()), idempotencyKey);
    }

    /** The path variable is the customer account. The settlement side is chosen here. */
    private void requireWallet(String accountNumber, String action) {
        AccountType type = accountRepository.findTypeByAccountNumber(accountNumber)
                .orElseThrow(() -> new NotFoundException("No account " + accountNumber));
        if (type == AccountType.SYSTEM) {
            throw new BusinessRuleException("Cannot " + action + " settlement account "
                    + accountNumber + ": pass the customer account number instead");
        }
    }

    private String currencyOf(String accountNumber) {
        return accountRepository.findCurrencyCodeByAccountNumber(accountNumber)
                .orElseThrow(() -> new NotFoundException("No account " + accountNumber));
    }

    private String systemAccountFor(String currencyCode) {
        return accountRepository.findSystemAccountNumber(currencyCode)
                .orElseThrow(() -> new NotFoundException("No settlement account for " + currencyCode));
    }
}
