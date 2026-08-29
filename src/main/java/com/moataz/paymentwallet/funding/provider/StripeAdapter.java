package com.moataz.paymentwallet.funding.provider;

import com.moataz.paymentwallet.common.error.ProviderUnavailableException;
import com.moataz.paymentwallet.funding.PaymentDirection;
import com.moataz.paymentwallet.funding.PaymentIntentStatus;
import com.moataz.paymentwallet.funding.PaymentProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stripe, via Checkout Sessions for collection and Payouts for disbursement.
 *
 * Two details here are the whole reason this adapter is not three lines. Stripe counts money
 * in integer minor units while this ledger keeps NUMERIC(19,4), and Stripe signs its webhooks
 * over a timestamped payload rather than the payload alone.
 */
@Component
@RequiredArgsConstructor
public class StripeAdapter implements PaymentProviderAdapter {

    private static final Duration SIGNATURE_TOLERANCE = Duration.ofMinutes(5);

    private final ObjectMapper objectMapper;

    @Value("${funding.base-url:}")
    private String baseUrl;

    @Value("${funding.stripe.secret-key:}")
    private String secretKey;

    @Value("${funding.stripe.webhook-secret:}")
    private String webhookSecret;

    @Value("${funding.stripe.api-url:https://api.stripe.com}")
    private String apiUrl;

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.STRIPE;
    }

    @Override
    public boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank();
    }

    @Override
    public Set<PaymentDirection> supports() {
        return Set.of(PaymentDirection.DEPOSIT, PaymentDirection.WITHDRAWAL);
    }

    @Override
    public ProviderHandle createCharge(PaymentContext payment) {
        long minor = Money.toMinorUnits(payment.amount(), payment.minorUnits());
        String currency = payment.currencyCode().toLowerCase(Locale.ROOT);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("mode", "payment");
        form.add("client_reference_id", payment.reference());
        form.add("success_url", baseUrl + "/funding/return/" + payment.reference() + "?outcome=success");
        form.add("cancel_url", baseUrl + "/funding/return/" + payment.reference() + "?outcome=cancel");
        form.add("line_items[0][quantity]", "1");
        form.add("line_items[0][price_data][currency]", currency);
        form.add("line_items[0][price_data][unit_amount]", Long.toString(minor));
        form.add("line_items[0][price_data][product_data][name]", "Wallet top-up");
        form.add("metadata[reference]", payment.reference());

        JsonNode response = post("/v1/checkout/sessions", form);
        return ProviderHandle.redirect(response.path("id").asString(),
                                       response.path("url").asString());
    }

    @Override
    public ProviderHandle createPayout(PaymentContext payment) {
        long minor = Money.toMinorUnits(payment.amount(), payment.minorUnits());

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("amount", Long.toString(minor));
        form.add("currency", payment.currencyCode().toLowerCase(Locale.ROOT));
        form.add("metadata[reference]", payment.reference());

        JsonNode response = post("/v1/payouts", form);
        return ProviderHandle.pending(response.path("id").asString());
    }

    @Override
    public ProviderEvent readEvent(String rawBody, Map<String, String> headers) {
        verifySignature(rawBody, headers.get("stripe-signature"));

        JsonNode json = objectMapper.readTree(rawBody);
        String type = json.path("type").asString("");
        JsonNode object = json.path("data").path("object");

        return new ProviderEvent(
                json.path("id").asString(),
                type,
                object.path("id").asString(),
                switch (type) {
                    case "checkout.session.completed",
                         "checkout.session.async_payment_succeeded",
                         "payout.paid" -> PaymentIntentStatus.SUCCEEDED;
                    case "checkout.session.async_payment_failed",
                         "payout.failed" -> PaymentIntentStatus.FAILED;
                    case "checkout.session.expired" -> PaymentIntentStatus.EXPIRED;
                    default -> null;
                },
                object.path("failure_message").asString(null));
    }

    /**
     * Stripe signs "timestamp.body", not the body, so a captured webhook cannot be replayed
     * tomorrow: the timestamp is inside what was signed and is checked against the clock.
     */
    void verifySignature(String rawBody, String header) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new WebhookVerificationException(
                    "No Stripe webhook secret configured: refusing to trust an unverified webhook");
        }
        if (header == null || header.isBlank()) {
            throw new WebhookVerificationException("Missing Stripe-Signature header");
        }

        String timestamp = null;
        boolean matched = false;
        for (String part : header.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2) {
                continue;
            }
            if ("t".equals(pair[0])) {
                timestamp = pair[1];
            }
        }
        if (timestamp == null) {
            throw new WebhookVerificationException("Stripe-Signature carries no timestamp");
        }

        String expected = Hmac.sha256Hex(webhookSecret, timestamp + "." + rawBody);
        for (String part : header.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && "v1".equals(pair[0]) && Hmac.matches(expected, pair[1])) {
                matched = true;
            }
        }
        if (!matched) {
            throw new WebhookVerificationException("Stripe webhook signature does not match");
        }

        long age = Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp));
        if (age > SIGNATURE_TOLERANCE.toSeconds()) {
            throw new WebhookVerificationException(
                    "Stripe webhook is " + age + "s old, outside the replay tolerance");
        }
    }

    private JsonNode post(String path, MultiValueMap<String, String> form) {
        if (!isConfigured()) {
            throw new ProviderUnavailableException(
                    "Stripe is not configured: set funding.stripe.secret-key");
        }
        try {
            String body = RestClient.builder().baseUrl(apiUrl).build()
                    .post()
                    .uri(path)
                    .header("Authorization", "Bearer " + secretKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(body);
        } catch (RestClientException ex) {
            throw new ProviderUnavailableException("Stripe " + path + " failed: " + ex.getMessage(), ex);
        }
    }
}
