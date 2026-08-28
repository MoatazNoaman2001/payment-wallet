package com.moataz.paymentwallet.transfer;

import com.moataz.paymentwallet.account.Account;
import com.moataz.paymentwallet.account.AccountStatus;
import com.moataz.paymentwallet.account.AccountType;
import com.moataz.paymentwallet.common.error.BusinessRuleException;
import com.moataz.paymentwallet.transfer.dto.TransferRequest;
import com.moataz.paymentwallet.transfer.dto.TransferResponse;
import com.moataz.paymentwallet.user.AppUser;
import com.moataz.paymentwallet.user.KycLimits;
import com.moataz.paymentwallet.user.KycTier;
import com.moataz.paymentwallet.user.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class TransferSupport {
    private final TransferRepository transferRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final KycLimits kycLimits;

    TransferResponse post(TransferRequest request, String idempotencyKey, UUID actorPublicId,
                          AppUser initiator, Account source, Account dest) {
        validate(request, source, dest);

        Transfer transfer = new Transfer();
        transfer.setReference(newReference());
        transfer.setIdempotencyKey(idempotencyKey);
        transfer.setInitiatedBy(initiator);
        transfer.setSourceAccount(source);
        transfer.setDestAccount(dest);
        transfer.setAmount(request.amount());
        transfer.setCurrency(source.getCurrency());
        transfer.setFee(BigDecimal.ZERO);
        transfer.setType(request.type());
        transfer.setDescription(request.description());
        transfer.setStatus(TransferStatus.PENDING);

        try {
            transferRepository.saveAndFlush(transfer);
        } catch (DataIntegrityViolationException ex) {
            return transferRepository.findByInitiatorAndKey(actorPublicId, idempotencyKey)
                    .map(TransferResponse::from)
                    .orElseThrow(() -> ex);
        }

        BigDecimal newSourceBalance = source.getBalance().subtract(request.amount());
        BigDecimal newDestBalance = dest.getBalance().add(request.amount());

        ledgerEntryRepository.save(leg(transfer, source, LedgerDirection.DEBIT, request.amount(), newSourceBalance));
        ledgerEntryRepository.save(leg(transfer, dest, LedgerDirection.CREDIT, request.amount(), newDestBalance));

        source.setBalance(newSourceBalance);
        dest.setBalance(newDestBalance);

        transfer.setStatus(TransferStatus.POSTED);
        transfer.setPostedAt(OffsetDateTime.now(ZoneOffset.UTC));

        outboxEventRepository.save(postedEvent(transfer));

        return TransferResponse.from(transfer);
    }

    private void validate(TransferRequest request, Account source, Account dest) {
        requireActiveHolder(source, "Sender");
        requireActiveHolder(dest, "Recipient");
        if (source.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessRuleException("Source account is " + source.getStatus());
        }
        if (dest.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessRuleException("Destination account is " + dest.getStatus());
        }
        if (!source.getCurrency().getCode().equals(dest.getCurrency().getCode())) {
            throw new BusinessRuleException("Currency mismatch: "
                    + source.getCurrency().getCode() + " -> " + dest.getCurrency().getCode());
        }
        if (!source.getCurrency().getCode().equals(request.currencyCode())) {
            throw new BusinessRuleException("Transfer currency " + request.currencyCode()
                    + " does not match account currency " + source.getCurrency().getCode());
        }
        if (source.getType() != AccountType.SYSTEM
                && source.getBalance().compareTo(request.amount()) < 0) {
            throw new BusinessRuleException("Insufficient funds: balance "
                    + source.getBalance() + ", requested " + request.amount());
        }
        DailyCap cap = dailyCap(source);
        if (cap != null) {
            OffsetDateTime startOfDay = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);
            BigDecimal spentToday = transferRepository.sumPostedSince(source.getId(), startOfDay);
            if (spentToday.add(request.amount()).compareTo(cap.amount()) > 0) {
                throw new BusinessRuleException("Daily limit exceeded: " + spentToday
                        + " already sent, limit " + cap.amount() + " (" + cap.reason() + ")");
            }
        }
    }

    /**
     * How much may leave today: the tighter of what the account was opened with and what the
     * holder's KYC tier allows. Settlement accounts are infrastructure and have neither.
     */
    private DailyCap dailyCap(Account source) {
        if (source.getType() == AccountType.SYSTEM) {
            return null;
        }
        DailyCap cap = source.getDailyLimit() == null
                ? null
                : new DailyCap(source.getDailyLimit(), "account limit");

        KycTier tier = kycLimits.effectiveTier(source.getUser());
        BigDecimal ceiling = kycLimits.ceilingFor(tier).orElse(null);
        if (ceiling != null && (cap == null || ceiling.compareTo(cap.amount()) < 0)) {
            cap = new DailyCap(ceiling, tier + " verification");
        }
        return cap;
    }

    private record DailyCap(BigDecimal amount, String reason) {
    }

    private void requireActiveHolder(Account account, String side) {
        UserStatus status = account.getUser().getStatus();
        if (status != UserStatus.ACTIVE) {
            throw new BusinessRuleException(side + " is not an active account holder ("
                    + status + "): identity must be verified before money can move");
        }
    }

    private LedgerEntry leg(Transfer transfer, Account account, LedgerDirection direction,
                            BigDecimal amount, BigDecimal balanceAfter) {
        LedgerEntry entry = new LedgerEntry();
        entry.setTransfer(transfer);
        entry.setAccount(account);
        entry.setDirection(direction);
        entry.setAmount(amount);
        entry.setBalanceAfter(balanceAfter);
        return entry;
    }

    private OutboxEvent postedEvent(Transfer t) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("Transfer");
        event.setAggregateId(t.getReference());
        event.setEventType("TransferPosted");
        event.setPayload(("{\"reference\":\"%s\",\"amount\":\"%s\",\"currency\":\"%s\","
                        + "\"source\":\"%s\",\"dest\":\"%s\"}")
                .formatted(t.getReference(), t.getAmount(), t.getCurrency().getCode(),
                           t.getSourceAccount().getAccountNumber(),
                           t.getDestAccount().getAccountNumber()));
        return event;
    }

    private String newReference() {
        return "TRF" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
    }
}
