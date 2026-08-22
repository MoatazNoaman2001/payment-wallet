package com.luv2code.paymentwallet.transfer;

import com.luv2code.paymentwallet.account.Account;
import com.luv2code.paymentwallet.account.AccountRepository;
import com.luv2code.paymentwallet.account.AccountStatus;
import com.luv2code.paymentwallet.common.error.BusinessRuleException;
import com.luv2code.paymentwallet.common.error.NotFoundException;
import com.luv2code.paymentwallet.transfer.dto.TransferResponse;
import com.luv2code.paymentwallet.user.AppUser;
import com.luv2code.paymentwallet.user.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A reversal never deletes or edits anything. It writes a new transfer in the opposite
 * direction with its own pair of ledger legs, and marks the original REVERSED. The audit
 * trail then shows both what happened and that it was undone, which is the whole point of
 * an append-only ledger.
 */
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

        // idempotent: asking twice returns the reversal that already exists
        var alreadyReversed = transferRepository.findReversalOf(reference);
        if (alreadyReversed.isPresent()) {
            return TransferResponse.from(alreadyReversed.get());
        }

        Transfer original = transferRepository.findByReference(reference)
                .orElseThrow(() -> new NotFoundException("No transfer " + reference));

        requireReversible(original);

        AppUser actor = userRepository.findByPublicId(actorPublicId)
                .orElseThrow(() -> new NotFoundException("No user with id " + actorPublicId));

        // same deterministic order as TransferService: lowest account id first
        Long originalSourceId = original.getSourceAccount().getId();
        Long originalDestId = original.getDestAccount().getId();
        Account first = lock(Math.min(originalSourceId, originalDestId));
        Account second = lock(Math.max(originalSourceId, originalDestId));
        Account refundTo = first.getId().equals(originalSourceId) ? first : second;   // was debited
        Account clawBackFrom = first.getId().equals(originalDestId) ? first : second; // was credited

        BigDecimal amount = original.getAmount();

        if (clawBackFrom.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessRuleException("Cannot reverse: account "
                    + clawBackFrom.getAccountNumber() + " is " + clawBackFrom.getStatus());
        }
        // the awkward real-world case: the recipient already spent it
        if (clawBackFrom.getType() != com.luv2code.paymentwallet.account.AccountType.SYSTEM
                && clawBackFrom.getBalance().compareTo(amount) < 0) {
            throw new BusinessRuleException("Cannot reverse: " + clawBackFrom.getAccountNumber()
                    + " holds " + clawBackFrom.getBalance() + " but the transfer was " + amount
                    + ". Recovering spent funds is a collections problem, not a ledger one.");
        }

        Transfer reversal = new Transfer();
        reversal.setReference(newReference());
        reversal.setIdempotencyKey("REVERSAL-" + original.getReference());
        reversal.setInitiatedBy(actor);
        reversal.setSourceAccount(clawBackFrom);      // opposite direction
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
            // lost the race on uq_transfer_reversal: someone reversed it a moment ago
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

    /** The allowed transitions: only POSTED -> REVERSED, and a reversal is never itself reversed. */
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
