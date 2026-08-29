package com.moataz.paymentwallet.funding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    Optional<WebhookEvent> findByProviderAndProviderEventId(PaymentProvider provider, String eventId);

    long countByProcessedAtIsNull();
}
