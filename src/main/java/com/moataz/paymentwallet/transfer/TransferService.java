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

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TransferService {
    private final TransferRepository transferRepository;
    private final AccountRepository accountRepository;
    private final AppUserRepository userRepository;
    private final TagRepository tagRepository;
    private final TransferSupport support;

    @Transactional
    public TransferResponse execute(TransferRequest request, String idempotencyKey, UUID actorPublicId) {
        var existing = transferRepository.findByInitiatorAndKey(actorPublicId, idempotencyKey);
        if (existing.isPresent()) {
            return TransferResponse.from(existing.get());
        }

        AppUser initiator = userRepository.findByPublicId(actorPublicId)
                .orElseThrow(() -> new NotFoundException("No user with id " + actorPublicId));

        Long sourceId = accountIdOf(request.sourceAccountNumber());
        Long destId = accountIdOf(request.destAccountNumber());
        if (sourceId.equals(destId)) {
            throw new BusinessRuleException("Source and destination must differ");
        }

        Account first = lock(Math.min(sourceId, destId));
        Account second = lock(Math.max(sourceId, destId));
        Account source = first.getId().equals(sourceId) ? first : second;
        Account dest = first.getId().equals(destId) ? first : second;

        return support.post(request, idempotencyKey, actorPublicId, initiator, source, dest);
    }

    @Transactional(readOnly = true)
    public TransferResponse findByReference(String reference) {
        return transferRepository.findByReference(reference)
                .map(TransferResponse::from)
                .orElseThrow(() -> new NotFoundException("No transfer " + reference));
    }

    @Transactional
    public List<String> replaceTags(String reference, Set<String> tagNames) {
        Transfer transfer = transferRepository.findWithTagsByReference(reference)
                .orElseThrow(() -> new NotFoundException("No transfer " + reference));

        List<Tag> found = tagRepository.findByNameIn(tagNames);
        if (found.size() != tagNames.size()) {
            Set<String> known = found.stream().map(Tag::getName).collect(Collectors.toSet());
            Set<String> unknown = new TreeSet<>(tagNames);
            unknown.removeAll(known);
            throw new NotFoundException("Unknown tags: " + unknown);
        }

        transfer.getTags().clear();
        transfer.getTags().addAll(found);
        return found.stream().map(Tag::getName).sorted().toList();
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
