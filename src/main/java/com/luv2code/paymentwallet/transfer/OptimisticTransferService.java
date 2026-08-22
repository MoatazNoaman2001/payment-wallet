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

/**
 * Optimistic strategy: no database locks. Both accounts are read normally, and
 * Account.version turns each balance UPDATE into
 *   update account set balance=?, version=? where id=? and version=?
 * A concurrent writer that got there first leaves version stale, zero rows match,
 * and Hibernate throws OptimisticLockingFailureException at flush.
 *
 * Retrying is the caller's job — see RetryingTransferService.
 */
@Service
@RequiredArgsConstructor
public class OptimisticTransferService {

    private final TransferRepository transferRepository;
    private final AccountRepository accountRepository;
    private final AppUserRepository userRepository;
    private final TransferSupport support;

    @Transactional
    public TransferResponse attempt(TransferRequest request, String idempotencyKey) {

        var existing = transferRepository.findByInitiatorAndKey(request.initiatorPublicId(), idempotencyKey);
        if (existing.isPresent()) {
            return TransferResponse.from(existing.get());
        }

        AppUser initiator = userRepository.findByPublicId(request.initiatorPublicId())
                .orElseThrow(() -> new NotFoundException("No user with id " + request.initiatorPublicId()));

        Account source = accountRepository.findByAccountNumber(request.sourceAccountNumber())
                .orElseThrow(() -> new NotFoundException("No account " + request.sourceAccountNumber()));
        Account dest = accountRepository.findByAccountNumber(request.destAccountNumber())
                .orElseThrow(() -> new NotFoundException("No account " + request.destAccountNumber()));

        if (source.getId().equals(dest.getId())) {
            throw new BusinessRuleException("Source and destination must differ");
        }

        return support.post(request, idempotencyKey, initiator, source, dest);
    }
}
