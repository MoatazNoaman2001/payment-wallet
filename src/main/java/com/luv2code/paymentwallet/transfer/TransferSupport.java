package com.luv2code.paymentwallet.transfer;

import com.luv2code.paymentwallet.account.Account;
import com.luv2code.paymentwallet.account.AccountStatus;
import com.luv2code.paymentwallet.account.AccountType;
import com.luv2code.paymentwallet.common.error.BusinessRuleException;
import com.luv2code.paymentwallet.transfer.dto.TransferRequest;
import com.luv2code.paymentwallet.transfer.dto.TransferResponse;
import com.luv2code.paymentwallet.user.AppUser;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Everything both locking strategies do identically. The only difference between
 * TransferService and OptimisticTransferService is how they obtain the two accounts.
 */
@Component
@RequiredArgsConstructor
class TransferSupport {

    private final TransferRepository transferRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final OutboxEventRepository outboxEventRepository;

    TransferResponse post(TransferRequest request, String idempotencyKey,
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
            return transferRepository.findByInitiatorAndKey(request.initiatorPublicId(), idempotencyKey)
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
        // a SYSTEM settlement account is allowed to go negative; a wallet is not
        if (source.getType() != AccountType.SYSTEM
                && source.getBalance().compareTo(request.amount()) < 0) {
            throw new BusinessRuleException("Insufficient funds: balance "
                    + source.getBalance() + ", requested " + request.amount());
        }
        if (source.getDailyLimit() != null) {
            OffsetDateTime startOfDay = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);
            BigDecimal spentToday = transferRepository.sumPostedSince(source.getId(), startOfDay);
            if (spentToday.add(request.amount()).compareTo(source.getDailyLimit()) > 0) {
                throw new BusinessRuleException("Daily limit exceeded: "
                        + spentToday + " already sent, limit " + source.getDailyLimit());
            }
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
