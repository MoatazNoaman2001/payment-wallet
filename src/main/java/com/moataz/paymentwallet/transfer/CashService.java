package com.moataz.paymentwallet.transfer;

import com.moataz.paymentwallet.account.AccountRepository;
import com.moataz.paymentwallet.account.AccountType;
import com.moataz.paymentwallet.common.error.BusinessRuleException;
import com.moataz.paymentwallet.common.error.NotFoundException;
import com.moataz.paymentwallet.transfer.dto.CashRequest;
import com.moataz.paymentwallet.transfer.dto.TransferRequest;
import com.moataz.paymentwallet.transfer.dto.TransferResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CashService {
    private final TransferService transferService;
    private final AccountRepository accountRepository;

    public TransferResponse deposit(String accountNumber, UUID actorPublicId,
                                    CashRequest request, String idempotencyKey) {
        requireWallet(accountNumber, "deposit into");
        String currency = currencyOf(accountNumber);
        return transferService.execute(new TransferRequest(
                systemAccountFor(currency),
                accountNumber,
                request.amount(),
                currency,
                TransferType.TOPUP,
                request.description()), idempotencyKey, actorPublicId);
    }

    public TransferResponse withdraw(String accountNumber, UUID actorPublicId,
                                     CashRequest request, String idempotencyKey) {
        requireWallet(accountNumber, "withdraw from");
        String currency = currencyOf(accountNumber);
        return transferService.execute(new TransferRequest(
                accountNumber,
                systemAccountFor(currency),
                request.amount(),
                currency,
                TransferType.WITHDRAWAL,
                request.description()), idempotencyKey, actorPublicId);
    }

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
