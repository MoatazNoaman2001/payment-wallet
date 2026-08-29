package com.moataz.paymentwallet.funding;

import com.moataz.paymentwallet.account.Account;
import com.moataz.paymentwallet.account.AccountRepository;
import com.moataz.paymentwallet.account.AccountType;
import com.moataz.paymentwallet.common.error.BusinessRuleException;
import com.moataz.paymentwallet.common.error.NotFoundException;
import com.moataz.paymentwallet.funding.dto.PaymentIntentResponse;
import com.moataz.paymentwallet.funding.provider.PaymentContext;
import com.moataz.paymentwallet.funding.provider.ProviderHandle;
import com.moataz.paymentwallet.transfer.Transfer;
import com.moataz.paymentwallet.transfer.TransferRepository;
import com.moataz.paymentwallet.transfer.TransferService;
import com.moataz.paymentwallet.transfer.TransferType;
import com.moataz.paymentwallet.transfer.dto.TransferRequest;
import com.moataz.paymentwallet.user.AppUser;
import com.moataz.paymentwallet.user.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The wallet's own books, for money that comes from or goes to somebody else.
 *
 * Every method here is one short transaction. That is deliberate: the call to Stripe or PayPal
 * happens between them, and holding a database transaction open across a network call to a third
 * party is how you exhaust a connection pool the first time they have a slow day.
 */
@Service
@RequiredArgsConstructor
public class FundingLedger {

    private static final Logger log = LoggerFactory.getLogger(FundingLedger.class);
    private static final String SYSTEM_EMAIL = "system@paymentwallet.local";

    private final PaymentIntentRepository intentRepository;
    private final AccountRepository accountRepository;
    private final AppUserRepository userRepository;
    private final TransferRepository transferRepository;
    private final TransferService transferService;

    /**
     * A withdrawal debits the wallet here, before the provider is even asked. Money that has
     * been promised to somebody outside must stop being spendable inside immediately, and
     * checking the balance without reserving it is a race, not a check.
     */
    @Transactional
    public PaymentContext open(UUID ownerPublicId, String accountNumber, PaymentProvider provider,
                              PaymentDirection direction, BigDecimal amount, String method,
                              String idempotencyKey) {

        AppUser owner = userRepository.findByPublicId(ownerPublicId)
                .orElseThrow(() -> new NotFoundException("No user with id " + ownerPublicId));

        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new NotFoundException("No account " + accountNumber));

        if (account.getType() == AccountType.SYSTEM) {
            throw new BusinessRuleException(
                    "Settlement accounts are funded by the ledger, not by a payment provider");
        }
        if (!account.getUser().getPublicId().equals(ownerPublicId)) {
            throw new BusinessRuleException("That account belongs to somebody else");
        }

        PaymentIntent intent = new PaymentIntent();
        intent.setReference(newReference(direction));
        intent.setUser(owner);
        intent.setAccount(account);
        intent.setDirection(direction);
        intent.setProvider(provider);
        intent.setMethod(method);
        intent.setAmount(amount);
        intent.setCurrency(account.getCurrency());
        intent.setStatus(PaymentIntentStatus.REQUIRES_ACTION);
        intent.setIdempotencyKey(idempotencyKey);
        intent.setInitiatedBy(owner);
        intentRepository.saveAndFlush(intent);

        if (direction == PaymentDirection.WITHDRAWAL) {
            // the engine enforces balance, frozen accounts and the KYC daily ceiling here
            Transfer debit = post(account.getAccountNumber(),
                                  clearingAccount(provider, account),
                                  amount, account.getCurrency().getCode(),
                                  TransferType.WITHDRAWAL,
                                  "Withdrawal via " + provider,
                                  intent.getReference());
            intent.setTransfer(debit);
            intent.setStatus(PaymentIntentStatus.PENDING);
        }

        return PaymentContext.of(intent);
    }

    /**
     * Lives here, not on FundingService, because the response is assembled while a session is
     * still open. A read like this called from another method of the same bean would get no
     * transaction at all, and building the DTO would touch a detached lazy association.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<PaymentIntentResponse> findByKey(UUID ownerPublicId, String idempotencyKey) {
        return intentRepository.findByInitiatorAndKey(ownerPublicId, idempotencyKey)
                .map(PaymentIntentResponse::from);
    }

    @Transactional
    public PaymentIntentResponse attach(String reference, ProviderHandle handle) {
        PaymentIntent intent = load(reference);
        intent.setProviderReference(handle.providerReference());
        intent.setRedirectUrl(handle.redirectUrl());
        intent.setDepositAddress(handle.depositAddress());
        if (intent.getStatus().isOpen() && handle.status() != null) {
            intent.setStatus(handle.status());
        }
        return PaymentIntentResponse.from(intent);
    }

    /**
     * The provider refused, or never answered. A deposit simply never happened; a withdrawal
     * has already left the wallet, so the money is put back with a compensating credit rather
     * than by editing the original entry. The ledger is append-only.
     */
    @Transactional
    public PaymentIntentResponse abandon(String reference, String failureReason) {
        PaymentIntent intent = load(reference);
        if (intent.getStatus().isTerminal()) {
            return PaymentIntentResponse.from(intent);
        }
        refundIfDebited(intent, "Failed withdrawal returned");
        intent.setStatus(PaymentIntentStatus.FAILED);
        intent.setFailureReason(truncate(failureReason));
        return PaymentIntentResponse.from(intent);
    }

    /**
     * A provider has spoken. This is the only place a deposit ever reaches the ledger, and it
     * runs under a row lock so a webhook and a status poll arriving together cannot both post.
     */
    @Transactional
    public PaymentIntentResponse applyOutcome(PaymentProvider provider, String providerReference,
                                              PaymentIntentStatus outcome, String failureReason) {

        PaymentIntent intent = intentRepository
                .findByProviderReferenceForUpdate(provider, providerReference)
                .orElseThrow(() -> new NotFoundException(
                        "No " + provider + " payment " + providerReference));

        if (intent.getStatus().isTerminal()) {
            log.info("Ignoring {} for {}: already {}", outcome, intent.getReference(), intent.getStatus());
            return PaymentIntentResponse.from(intent);
        }

        switch (outcome) {
            case SUCCEEDED -> confirm(intent);
            case FAILED, EXPIRED, CANCELLED -> {
                refundIfDebited(intent, "Returned " + outcome.name().toLowerCase() + " withdrawal");
                intent.setStatus(outcome);
                intent.setFailureReason(truncate(failureReason));
            }
            default -> log.info("Nothing to do for {} on {}", outcome, intent.getReference());
        }

        intent.setConfirmedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return PaymentIntentResponse.from(intent);
    }

    private void confirm(PaymentIntent intent) {
        if (intent.isDeposit()) {
            Transfer credit = post(clearingAccount(intent.getProvider(), intent.getAccount()),
                                   intent.getAccount().getAccountNumber(),
                                   intent.getAmount(),
                                   intent.getCurrency().getCode(),
                                   TransferType.TOPUP,
                                   "Deposit via " + intent.getProvider(),
                                   intent.getReference());
            intent.setTransfer(credit);
        }
        // a withdrawal was already debited when the intent was opened; success changes no money
        intent.setStatus(PaymentIntentStatus.SUCCEEDED);
    }

    private void refundIfDebited(PaymentIntent intent, String description) {
        if (intent.isDeposit() || intent.getTransfer() == null) {
            return;
        }
        post(clearingAccount(intent.getProvider(), intent.getAccount()),
             intent.getAccount().getAccountNumber(),
             intent.getAmount(),
             intent.getCurrency().getCode(),
             TransferType.REVERSAL,
             description + " " + intent.getReference(),
             intent.getReference() + "-return");
        log.info("Returned {} {} to {} after a failed withdrawal",
                 intent.getAmount(), intent.getCurrency().getCode(),
                 intent.getAccount().getAccountNumber());
    }

    private Transfer post(String from, String to, BigDecimal amount, String currencyCode,
                          TransferType type, String description, String idempotencyKey) {
        String reference = transferService.execute(
                new TransferRequest(from, to, amount, currencyCode, type, description),
                idempotencyKey, systemUser().getPublicId()).reference();
        return transferRepository.findByReference(reference)
                .orElseThrow(() -> new IllegalStateException("Just-posted transfer " + reference + " is gone"));
    }

    private String clearingAccount(PaymentProvider provider, Account account) {
        return accountRepository
                .findClearingAccountNumber(provider.name(), account.getCurrency().getCode())
                .orElseThrow(() -> new NotFoundException(
                        "No " + provider + " clearing account for " + account.getCurrency().getCode()));
    }

    private PaymentIntent load(String reference) {
        return intentRepository.findByReferenceForUpdate(reference)
                .orElseThrow(() -> new NotFoundException("No payment intent " + reference));
    }

    private AppUser systemUser() {
        return userRepository.findByEmailWithRoles(SYSTEM_EMAIL)
                .orElseThrow(() -> new IllegalStateException("System user missing"));
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 255 ? reason : reason.substring(0, 255);
    }

    private static String newReference(PaymentDirection direction) {
        String prefix = direction == PaymentDirection.DEPOSIT ? "DEP" : "WDR";
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
    }
}
