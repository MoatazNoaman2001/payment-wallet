package com.moataz.paymentwallet.funding;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, Long> {

    Optional<PaymentIntent> findByReference(String reference);

    @Query("""
           select i from PaymentIntent i join i.user u
           where u.publicId = :publicId and i.idempotencyKey = :idempotencyKey
           """)
    Optional<PaymentIntent> findByInitiatorAndKey(UUID publicId, String idempotencyKey);

    /**
     * A webhook and a status poll can arrive at the same moment for the same intent, and both
     * want to post to the ledger. The row is locked so exactly one of them wins.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from PaymentIntent i where i.provider = :provider and i.providerReference = :reference")
    Optional<PaymentIntent> findByProviderReferenceForUpdate(PaymentProvider provider, String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from PaymentIntent i where i.reference = :reference")
    Optional<PaymentIntent> findByReferenceForUpdate(String reference);

    @Query("""
           select i from PaymentIntent i join i.user u
           where u.publicId = :publicId order by i.createdAt desc
           """)
    Page<PaymentIntent> findAllForUser(UUID publicId, Pageable pageable);

    @Query("""
           select i from PaymentIntent i
           where i.status in (com.moataz.paymentwallet.funding.PaymentIntentStatus.REQUIRES_ACTION,
                              com.moataz.paymentwallet.funding.PaymentIntentStatus.PENDING)
             and i.createdAt < :cutoff
           """)
    List<PaymentIntent> findStale(OffsetDateTime cutoff);

    long countByStatus(PaymentIntentStatus status);

    @Modifying
    @Query("delete from PaymentIntent i where i.account.id in :accountIds")
    void deleteByAccountIdIn(List<Long> accountIds);
}
