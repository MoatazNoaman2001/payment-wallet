package com.moataz.paymentwallet.transfer;

import com.moataz.paymentwallet.transfer.dto.TransferRequest;
import com.moataz.paymentwallet.transfer.dto.TransferResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The retry loop lives in a separate bean on purpose. If attempt() were called from
 * another method of OptimisticTransferService, the call would not pass through the
 * Spring proxy, so every retry would reuse the same failed transaction.
 *
 * Deliberately NOT @Transactional: each attempt needs its own transaction, because a
 * rolled-back one can never succeed.
 */
@Service
@RequiredArgsConstructor
public class RetryingTransferService {

    private static final int MAX_ATTEMPTS = 50;

    private final OptimisticTransferService optimisticTransferService;
    private final AtomicLong retries = new AtomicLong();

    public TransferResponse execute(TransferRequest request, String idempotencyKey, UUID actorPublicId) {
        OptimisticLockingFailureException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return optimisticTransferService.attempt(request, idempotencyKey, actorPublicId);
            } catch (OptimisticLockingFailureException ex) {
                last = ex;
                retries.incrementAndGet();
                backoff(attempt);
            }
        }
        throw last;
    }

    /** In production this would be a Micrometer counter. */
    public long retryCount() {
        return retries.get();
    }

    public void resetRetryCount() {
        retries.set(0);
    }

    private void backoff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(1, Math.min(1L << attempt, 50L) + 1));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying transfer", ie);
        }
    }
}
