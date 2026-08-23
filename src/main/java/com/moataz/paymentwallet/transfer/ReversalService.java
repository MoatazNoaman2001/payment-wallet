package com.moataz.paymentwallet.transfer;

import com.moataz.paymentwallet.account.Account;
import com.moataz.paymentwallet.account.AccountRepository;
import com.moataz.paymentwallet.account.AccountStatus;
import com.moataz.paymentwallet.common.error.BusinessRuleException;
import com.moataz.paymentwallet.common.error.NotFoundException;
import com.moataz.paymentwallet.transfer.dto.TransferResponse;
import com.moataz.paymentwallet.user.AppUser;
import com.moataz.paymentwallet.user.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReversalService {
    private final TransferRepository transferRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final AccountRepository accountRepository;
    private final AppUserRepository userRepository;

    @Transactional
    public TransferResponse reverse(String reference, String reason, UUID actorPublicId) {
        var alreadyReversed = transferRepository.findReversalOf(reference);
        if (alreadyReversed.isPresent()) {
            return TransferResponse.from(alreadyReversed.get());
        }

        Transfer original = transferRepository.findByReference(reference)
                .orElseThrow(() -> new NotFoundException("No transfer " + reference));

        requireReversible(original);

        AppUser actor = userRepository.findByPublicId(actorPublicId)
                .orElseThrow(() -> new NotFoundException("No user with id " + actorPublicId));

        Long originalSourceId = original.getSourceAccount().getId();
        Long originalDestId = original.getDestAccount().getId();
        Account first = lock(Math.min(originalSourceId, originalDestId));
        Account second = lock(Math.max(originalSourceId, originalDestId));
        Account refundTo = first.getId().equals(originalSourceId) ? first : second;
        Account clawBackFrom = first.getId().equals(originalDestId) ? first : second;

        BigDecimal amount = original.getAmount();

        if (clawBackFrom.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessRuleException("Cannot reverse: account "
                    + clawBackFrom.getAccountNumber() + " is " + clawBackFrom.getStatus());
        }
        if (clawBackFrom.getType() != com.moataz.paymentwallet.account.AccountType.SYSTEM
                && clawBackFrom.getBalance().compareTo(amount) < 0) {
            throw new BusinessRuleException("Cannot reverse: " + clawBackFrom.getAccountNumber()
                    + " holds " + clawBackFrom.getBalance() + " but the transfer was " + amount
                    + ". Recovering spent funds is a collections problem, not a ledger one.");
        }

        Transfer reversal = new Transfer();
        reversal.setReference(newReference());
        reversal.setIdempotencyKey("REVERSAL-" + original.getReference());
        reversal.setInitiatedBy(actor);
        reversal.setSourceAccount(clawBackFrom);
        reversal.setDestAccount(refundTo);
        reversal.setAmount(amount);
        reversal.setCurrency(original.getCurrency());
        reversal.setFee(BigDecimal.ZERO);
        reversal.setType(TransferType.REVERSAL);
        reversal.setStatus(TransferStatus.PENDING);
        reversal.setDescription("Reversal of " + original.getReference() + ": " + reason);
        reversal.setReversesTransfer(original);

        try {
            transferRepository.saveAndFlush(reversal);
        } catch (DataIntegrityViolationException ex) {
            return transferRepository.findReversalOf(reference)
                    .map(TransferResponse::from)
                    .orElseThrow(() -> ex);
        }

        BigDecimal clawBackBalance = clawBackFrom.getBalance().subtract(amount);
        BigDecimal refundBalance = refundTo.getBalance().add(amount);

        ledgerEntryRepository.save(leg(reversal, clawBackFrom, LedgerDirection.DEBIT, amount, clawBackBalance));
        ledgerEntryRepository.save(leg(reversal, refundTo, LedgerDirection.CREDIT, amount, refundBalance));

        clawBackFrom.setBalance(clawBackBalance);
        refundTo.setBalance(refundBalance);

        reversal.setStatus(TransferStatus.POSTED);
        reversal.setPostedAt(OffsetDateTime.now(ZoneOffset.UTC));
        original.setStatus(TransferStatus.REVERSED);

        outboxEventRepository.save(reversedEvent(original, reversal, reason));

        return TransferResponse.from(reversal);
    }

    private void requireReversible(Transfer original) {
        if (original.getType() == TransferType.REVERSAL) {
            throw new BusinessRuleException("A reversal cannot itself be reversed");
        }
        if (original.getStatus() != TransferStatus.POSTED) {
            throw new BusinessRuleException("Only POSTED transfers can be reversed, this one is "
                    + original.getStatus());
        }
    }

    private Account lock(Long id) {
        return accountRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("No account with id " + id));
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

    private OutboxEvent reversedEvent(Transfer original, Transfer reversal, String reason) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateType("Transfer");
        event.setAggregateId(original.getReference());
        event.setEventType("TransferReversed");
        event.setPayload(("{\"original\":\"%s\",\"reversal\":\"%s\",\"amount\":\"%s\",\"reason\":\"%s\"}")
                .formatted(original.getReference(), reversal.getReference(),
                           original.getAmount(), reason.replace("\"", "'")));
        return event;
    }

    private String newReference() {
        return "REV" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
    }
}
