package com.moataz.paymentwallet.funding;

import com.moataz.paymentwallet.common.error.NotFoundException;
import com.moataz.paymentwallet.funding.dto.FundingRequest;
import com.moataz.paymentwallet.funding.dto.PaymentIntentResponse;
import com.moataz.paymentwallet.funding.dto.ProviderSummary;
import com.moataz.paymentwallet.funding.provider.PaymentContext;
import com.moataz.paymentwallet.funding.provider.PaymentProviderAdapter;
import com.moataz.paymentwallet.funding.provider.ProviderHandle;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Deposits and withdrawals that involve somebody else's system.
 *
 * Note what is <em>not</em> annotated here. This class talks to Stripe and PayPal over the
 * network, and none of that happens inside a database transaction: each write is a separate
 * short call into {@link FundingLedger}. A transaction held open across a third party's slow
 * afternoon is a connection pool waiting to run out.
 */
@Service
@RequiredArgsConstructor
public class FundingService {

    private static final Logger log = LoggerFactory.getLogger(FundingService.class);

    private final FundingLedger ledger;
    private final ProviderRegistry registry;
    private final PaymentIntentRepository intentRepository;

    public PaymentIntentResponse deposit(FundingRequest request, UUID ownerPublicId,
                                         String idempotencyKey) {
        return initiate(request, PaymentDirection.DEPOSIT, ownerPublicId, idempotencyKey);
    }

    public PaymentIntentResponse withdraw(FundingRequest request, UUID ownerPublicId,
                                          String idempotencyKey) {
        return initiate(request, PaymentDirection.WITHDRAWAL, ownerPublicId, idempotencyKey);
    }

    private PaymentIntentResponse initiate(FundingRequest request, PaymentDirection direction,
                                           UUID ownerPublicId, String idempotencyKey) {

        var existing = ledger.findByKey(ownerPublicId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        PaymentProviderAdapter adapter = registry.require(request.provider(), direction);

        // reserves the money for a withdrawal, and commits, before anyone external is called
        PaymentContext payment = ledger.open(ownerPublicId, request.accountNumber(),
                request.provider(), direction, request.amount(), request.method(), idempotencyKey);

        ProviderHandle handle;
        try {
            handle = direction == PaymentDirection.DEPOSIT
                    ? adapter.createCharge(payment)
                    : adapter.createPayout(payment);
        } catch (RuntimeException ex) {
            // the intent survives on purpose: a record of a payment that may or may not exist
            // out there is exactly what reconciliation needs. A rollback would erase it.
            log.warn("{} refused {}: {}", request.provider(), payment.reference(), ex.getMessage());
            ledger.abandon(payment.reference(), ex.getMessage());
            throw ex;
        }

        return ledger.attach(payment.reference(), handle);
    }

    @Transactional(readOnly = true)
    public PaymentIntentResponse find(String reference) {
        return intentRepository.findByReference(reference)
                .map(PaymentIntentResponse::from)
                .orElseThrow(() -> new NotFoundException("No payment " + reference));
    }

    @Transactional(readOnly = true)
    public Page<PaymentIntentResponse> history(UUID ownerPublicId, Pageable pageable) {
        return intentRepository.findAllForUser(ownerPublicId, pageable)
                .map(PaymentIntentResponse::from);
    }

    @Transactional(readOnly = true)
    public UUID ownerOf(String reference) {
        return intentRepository.findByReference(reference)
                .map(intent -> intent.getUser().getPublicId())
                .orElseThrow(() -> new NotFoundException("No payment " + reference));
    }

    public List<ProviderSummary> providers() {
        return registry.summaries();
    }

    public List<ProviderSummary> available(PaymentDirection direction) {
        return registry.available(direction);
    }
}
