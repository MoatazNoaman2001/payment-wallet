package com.luv2code.paymentwallet.transfer;

import com.luv2code.paymentwallet.account.Account;
import com.luv2code.paymentwallet.account.AccountRepository;
import com.luv2code.paymentwallet.common.error.BusinessRuleException;
import com.luv2code.paymentwallet.common.error.NotFoundException;
import com.luv2code.paymentwallet.transfer.dto.TransferRequest;
import com.luv2code.paymentwallet.transfer.dto.TransferResponse;
import com.luv2code.paymentwallet.user.AppUser;
import com.luv2code.paymentwallet.user.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pessimistic strategy: SELECT ... FOR UPDATE on both accounts before validating. */
@Service
@RequiredArgsConstructor
public class TransferService {

    private final TransferRepository transferRepository;
    private final AccountRepository accountRepository;
    private final AppUserRepository userRepository;
    private final TransferSupport support;

    @Transactional
    public TransferResponse execute(TransferRequest request, String idempotencyKey) {

        // 1. idempotency: a retry returns the original result, it does not move money twice
        var existing = transferRepository.findByInitiatorAndKey(request.initiatorPublicId(), idempotencyKey);
        if (existing.isPresent()) {
            return TransferResponse.from(existing.get());
        }

        AppUser initiator = userRepository.findByPublicId(request.initiatorPublicId())
                .orElseThrow(() -> new NotFoundException("No user with id " + request.initiatorPublicId()));

        Long sourceId = accountIdOf(request.sourceAccountNumber());
        Long destId = accountIdOf(request.destAccountNumber());
        if (sourceId.equals(destId)) {
            throw new BusinessRuleException("Source and destination must differ");
        }

        // 2. lock both rows, always lowest id first. Concurrent A->B and B->A transfers
        //    would deadlock if each locked its own source first.
        Account first = lock(Math.min(sourceId, destId));
        Account second = lock(Math.max(sourceId, destId));
        Account source = first.getId().equals(sourceId) ? first : second;
        Account dest = first.getId().equals(destId) ? first : second;

        // 3-5. validate, write both legs, update balances, emit the outbox event
        return support.post(request, idempotencyKey, initiator, source, dest);
    }

    @Transactional(readOnly = true)
    public TransferResponse findByReference(String reference) {
        return transferRepository.findByReference(reference)
                .map(TransferResponse::from)
                .orElseThrow(() -> new NotFoundException("No transfer " + reference));
    }

    private Long accountIdOf(String accountNumber) {
        return accountRepository.findIdByAccountNumber(accountNumber)
                .orElseThrow(() -> new NotFoundException("No account " + accountNumber));
    }

    private Account lock(Long id) {
        return accountRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("No account with id " + id));
    }
}
