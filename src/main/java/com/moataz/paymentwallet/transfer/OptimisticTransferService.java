package com.moataz.paymentwallet.transfer;

import com.moataz.paymentwallet.account.Account;
import com.moataz.paymentwallet.account.AccountRepository;
import com.moataz.paymentwallet.common.error.BusinessRuleException;
import com.moataz.paymentwallet.common.error.NotFoundException;
import com.moataz.paymentwallet.transfer.dto.TransferRequest;
import com.moataz.paymentwallet.transfer.dto.TransferResponse;
import com.moataz.paymentwallet.user.AppUser;
import com.moataz.paymentwallet.user.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OptimisticTransferService {
    private final TransferRepository transferRepository;
    private final AccountRepository accountRepository;
    private final AppUserRepository userRepository;
    private final TransferSupport support;

    @Transactional
    public TransferResponse attempt(TransferRequest request, String idempotencyKey, UUID actorPublicId) {
        var existing = transferRepository.findByInitiatorAndKey(actorPublicId, idempotencyKey);
        if (existing.isPresent()) {
            return TransferResponse.from(existing.get());
        }

        AppUser initiator = userRepository.findByPublicId(actorPublicId)
                .orElseThrow(() -> new NotFoundException("No user with id " + actorPublicId));

        Account source = accountRepository.findByAccountNumber(request.sourceAccountNumber())
                .orElseThrow(() -> new NotFoundException("No account " + request.sourceAccountNumber()));
        Account dest = accountRepository.findByAccountNumber(request.destAccountNumber())
                .orElseThrow(() -> new NotFoundException("No account " + request.destAccountNumber()));

        if (source.getId().equals(dest.getId())) {
            throw new BusinessRuleException("Source and destination must differ");
        }

        return support.post(request, idempotencyKey, actorPublicId, initiator, source, dest);
    }
}
