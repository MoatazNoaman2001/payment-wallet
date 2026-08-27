package com.moataz.paymentwallet.transfer;

import com.moataz.paymentwallet.account.AccountRepository;
import com.moataz.paymentwallet.account.AccountType;
import com.moataz.paymentwallet.common.error.BusinessRuleException;
import com.moataz.paymentwallet.common.error.NotFoundException;
import com.moataz.paymentwallet.user.AppUser;
import com.moataz.paymentwallet.user.AppUserRepository;
import com.moataz.paymentwallet.user.Role;
import com.moataz.paymentwallet.transfer.dto.CashRequest;
import org.springframework.beans.factory.annotation.Value;
import com.moataz.paymentwallet.transfer.dto.TransferRequest;
import com.moataz.paymentwallet.transfer.dto.TransferResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CashService {
    private final TransferService transferService;
    private final AccountRepository accountRepository;
    private final AppUserRepository userRepository;

    @Value("${limits.teller.max-cash:20000}")
    private BigDecimal tellerCashLimit;

    public TransferResponse deposit(String accountNumber, UUID actorPublicId,
                                    CashRequest request, String idempotencyKey) {
        requireWallet(accountNumber, "deposit into");
        requireNotSelfDealing(accountNumber, actorPublicId);
        requireWithinCashLimit(actorPublicId, request.amount());
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
        requireNotSelfDealing(accountNumber, actorPublicId);
        requireWithinCashLimit(actorPublicId, request.amount());
        String currency = currencyOf(accountNumber);
        return transferService.execute(new TransferRequest(
                accountNumber,
                systemAccountFor(currency),
                request.amount(),
                currency,
                TransferType.WITHDRAWAL,
                request.description()), idempotencyKey, actorPublicId);
    }

    private void requireNotSelfDealing(String accountNumber, UUID actorPublicId) {
        if (accountRepository.findByAccountNumberAndUserPublicId(accountNumber, actorPublicId).isPresent()) {
            throw new BusinessRuleException(
                    "Counter operations may not be performed on your own account. "
                    + "Ask a colleague to serve you.");
        }
    }

    private void requireWithinCashLimit(UUID actorPublicId, BigDecimal amount) {
        AppUser actor = userRepository.findByPublicId(actorPublicId)
                .orElseThrow(() -> new NotFoundException("No user with id " + actorPublicId));

        Set<String> roles = actor.getRoles().stream().map(Role::getName).collect(Collectors.toSet());
        boolean unlimited = roles.contains(Role.ADMIN) || roles.contains(Role.SUPERVISOR);

        if (!unlimited && roles.contains(Role.TELLER) && amount.compareTo(tellerCashLimit) > 0) {
            throw new BusinessRuleException(
                    "Above the teller limit of " + tellerCashLimit
                    + ": a supervisor must handle this amount");
        }
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
