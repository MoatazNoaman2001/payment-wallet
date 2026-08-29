package com.moataz.paymentwallet.funding.provider;

import com.moataz.paymentwallet.common.error.ProviderUnavailableException;
import com.moataz.paymentwallet.funding.PaymentDirection;
import com.moataz.paymentwallet.funding.PaymentIntentStatus;
import com.moataz.paymentwallet.funding.PaymentProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PayPal, via Orders v2 for collection and Payouts for disbursement.
 *
 * PayPal splits paying into two steps that Stripe Checkout hides: the buyer <em>approves</em>
 * an order, and the merchant then <em>captures</em> it. Approval alone moves no money, so the
 * event this wallet acts on is the capture, not the approval.
 */
@Component
@RequiredArgsConstructor
public class PayPalAdapter implements PaymentProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(PayPalAdapter.class);

    private final ObjectMapper objectMapper;

    @Value("${funding.base-url:}")
    private String baseUrl;

    @Value("${funding.paypal.client-id:}")
    private String clientId;

    @Value("${funding.paypal.client-secret:}")
    private String clientSecret;

    @Value("${funding.paypal.webhook-id:}")
    private String webhookId;

    @Value("${funding.paypal.api-url:https://api-m.sandbox.paypal.com}")
    private String apiUrl;

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.PAYPAL;
    }

    @Override
    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }

    @Override
    public Set<PaymentDirection> supports() {
        return Set.of(PaymentDirection.DEPOSIT, PaymentDirection.WITHDRAWAL);
    }

    @Override
    public ProviderHandle createCharge(PaymentContext payment) {
        String value = Money.toDecimalString(payment.amount(), payment.minorUnits());

        String body = """
                {
                  "intent": "CAPTURE",
                  "purchase_units": [{
                    "reference_id": "%s",
                    "amount": { "currency_code": "%s", "value": "%s" }
                  }],
                  "payment_source": { "paypal": { "experience_context": {
                    "user_action": "PAY_NOW",
                    "return_url": "%s/funding/return/%s?outcome=success",
                    "cancel_url": "%s/funding/return/%s?outcome=cancel"
                  }}}
                }
                """.formatted(payment.reference(), payment.currencyCode(), value,
                              baseUrl, payment.reference(), baseUrl, payment.reference());

        JsonNode response = postJson("/v2/checkout/orders", body);
        return ProviderHandle.redirect(response.path("id").asString(), approvalLink(response));
    }

    @Override
    public ProviderHandle createPayout(PaymentContext payment) {
        String value = Money.toDecimalString(payment.amount(), payment.minorUnits());

        String body = """
                {
                  "sender_batch_header": {
                    "sender_batch_id": "%s",
                    "email_subject": "A payout from your wallet"
                  },
                  "items": [{
                    "recipient_type": "EMAIL",
                    "receiver": "%s",
                    "note": "Wallet withdrawal %s",
                    "sender_item_id": "%s",
                    "amount": { "currency": "%s", "value": "%s" }
                  }]
                }
                """.formatted(payment.reference(), payment.customerEmail(),
                              payment.reference(), payment.reference(),
                              payment.currencyCode(), value);

        JsonNode response = postJson("/v1/payments/payouts", body);
        return ProviderHandle.pending(response.path("batch_header").path("payout_batch_id").asString());
    }

    @Override
    public ProviderEvent readEvent(String rawBody, Map<String, String> headers) {
        verifySignature(rawBody, headers);

        JsonNode json = objectMapper.readTree(rawBody);
        String type = json.path("event_type").asString("");
        JsonNode resource = json.path("resource");

        // a capture event names the capture, not the order the wallet is holding
        String reference = resource.path("supplementary_data").path("related_ids")
                                   .path("order_id").asString(null);
        if (reference == null) {
            reference = resource.path("id").asString();
        }

        return new ProviderEvent(
                json.path("id").asString(),
                type,
                reference,
                switch (type) {
                    case "PAYMENT.CAPTURE.COMPLETED", "PAYMENT.PAYOUTSBATCH.SUCCESS" ->
                            PaymentIntentStatus.SUCCEEDED;
                    case "PAYMENT.CAPTURE.DENIED", "PAYMENT.CAPTURE.DECLINED",
                         "PAYMENT.PAYOUTSBATCH.DENIED" -> PaymentIntentStatus.FAILED;
                    case "CHECKOUT.ORDER.VOIDED" -> PaymentIntentStatus.CANCELLED;
                    // approval is not money: the capture event below is what counts
                    default -> null;
                },
                resource.path("status_details").path("reason").asString(null));
    }

    /**
     * PayPal does not hand out an HMAC secret. Verification means asking PayPal itself whether
     * the delivery it just made was genuine, which costs a round trip per webhook — the price
     * of their certificate-based scheme.
     */
    void verifySignature(String rawBody, Map<String, String> headers) {
        if (webhookId == null || webhookId.isBlank()) {
            throw new WebhookVerificationException(
                    "No PayPal webhook id configured: refusing to trust an unverified webhook");
        }

        String body = """
                {
                  "auth_algo": "%s",
                  "cert_url": "%s",
                  "transmission_id": "%s",
                  "transmission_sig": "%s",
                  "transmission_time": "%s",
                  "webhook_id": "%s",
                  "webhook_event": %s
                }
                """.formatted(
                        header(headers, "paypal-auth-algo"),
                        header(headers, "paypal-cert-url"),
                        header(headers, "paypal-transmission-id"),
                        header(headers, "paypal-transmission-sig"),
                        header(headers, "paypal-transmission-time"),
                        webhookId,
                        rawBody);

        JsonNode response = postJson("/v1/notifications/verify-webhook-signature", body);
        if (!"SUCCESS".equals(response.path("verification_status").asString())) {
            throw new WebhookVerificationException("PayPal rejected the webhook signature");
        }
    }

    private static String header(Map<String, String> headers, String name) {
        String value = headers.get(name);
        if (value == null || value.isBlank()) {
            throw new WebhookVerificationException("Missing PayPal header " + name);
        }
        return value;
    }

    private static String approvalLink(JsonNode order) {
        for (JsonNode link : order.path("links")) {
            String rel = link.path("rel").asString("");
            if ("payer-action".equals(rel) || "approve".equals(rel)) {
                return link.path("href").asString();
            }
        }
        throw new ProviderUnavailableException("PayPal returned an order with no approval link");
    }

    private JsonNode postJson(String path, String body) {
        if (!isConfigured()) {
            throw new ProviderUnavailableException(
                    "PayPal is not configured: set funding.paypal.client-id and client-secret");
        }
        try {
            String response = client()
                    .post()
                    .uri(path)
                    .header("Authorization", "Bearer " + accessToken())
                    .header("PayPal-Request-Id", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(response);
        } catch (RestClientException ex) {
            throw new ProviderUnavailableException("PayPal " + path + " failed: " + ex.getMessage(), ex);
        }
    }

    private String accessToken() {
        String basic = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");

        try {
            String response = client()
                    .post()
                    .uri("/v1/oauth2/token")
                    .header("Authorization", "Basic " + basic)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(response).path("access_token").asString();
        } catch (RestClientException ex) {
            log.warn("PayPal token request failed: {}", ex.getMessage());
            throw new ProviderUnavailableException("PayPal authentication failed", ex);
        }
    }

    private RestClient client() {
        return RestClient.builder().baseUrl(apiUrl).build();
    }
}
