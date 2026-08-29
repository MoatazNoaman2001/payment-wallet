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
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Set;

/**
 * A crypto payment gateway, shaped like Coinbase Commerce or BTCPay: the wallet asks for a
 * charge, the gateway watches an address, and it calls back as the transaction gains
 * confirmations.
 *
 * Crypto is not simply a third card processor, and this adapter exists mainly to hold the two
 * differences that matter. There are no chargebacks, so a settled deposit is final in a way a
 * card deposit never is. And settlement is <em>gradual</em>: the money is visible on-chain long
 * before it is safe to credit, so an event that reports fewer than the required confirmations
 * changes nothing here. Crediting on sight is how exchanges lose money to reorganisations.
 */
@Component
@RequiredArgsConstructor
public class CryptoAdapter implements PaymentProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(CryptoAdapter.class);

    private final ObjectMapper objectMapper;

    @Value("${funding.crypto.api-url:}")
    private String apiUrl;

    @Value("${funding.crypto.api-key:}")
    private String apiKey;

    @Value("${funding.crypto.webhook-secret:}")
    private String webhookSecret;

    @Value("${funding.crypto.required-confirmations:3}")
    private int requiredConfirmations;

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.CRYPTO;
    }

    @Override
    public boolean isConfigured() {
        return apiUrl != null && !apiUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public Set<PaymentDirection> supports() {
        // paying out on chain needs custody of a hot wallet, which this demo does not have
        return Set.of(PaymentDirection.DEPOSIT);
    }

    @Override
    public ProviderHandle createCharge(PaymentContext payment) {
        String body = """
                {
                  "name": "Wallet top-up",
                  "pricing_type": "fixed_price",
                  "local_price": { "amount": "%s", "currency": "%s" },
                  "metadata": { "reference": "%s" }
                }
                """.formatted(
                        Money.toDecimalString(payment.amount(), payment.minorUnits()),
                        payment.currencyCode(),
                        payment.reference());

        JsonNode response = post("/charges", body);
        JsonNode charge = response.has("data") ? response.path("data") : response;

        String address = charge.path("addresses").properties().stream()
                .map(entry -> entry.getValue().asString())
                .findFirst()
                .orElse(null);

        String hosted = charge.path("hosted_url").asString(null);
        String reference = charge.path("id").asString();

        return hosted != null
                ? ProviderHandle.redirect(reference, hosted)
                : ProviderHandle.address(reference, address);
    }

    @Override
    public ProviderHandle createPayout(PaymentContext payment) {
        throw new ProviderUnavailableException(
                "On-chain payouts are not supported: this wallet holds no hot wallet keys");
    }

    @Override
    public ProviderEvent readEvent(String rawBody, Map<String, String> headers) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new WebhookVerificationException(
                    "No crypto webhook secret configured: refusing to trust an unverified webhook");
        }
        String signature = headers.get("x-cc-webhook-signature");
        if (!Hmac.matches(Hmac.sha256Hex(webhookSecret, rawBody), signature)) {
            throw new WebhookVerificationException("Crypto webhook signature does not match");
        }

        JsonNode json = objectMapper.readTree(rawBody);
        JsonNode event = json.has("event") ? json.path("event") : json;
        String type = event.path("type").asString("");
        JsonNode data = event.path("data");

        int confirmations = data.path("confirmations").asInt(0);
        PaymentIntentStatus outcome = switch (type) {
            case "charge:confirmed" -> PaymentIntentStatus.SUCCEEDED;
            case "charge:failed" -> PaymentIntentStatus.FAILED;
            case "charge:expired" -> PaymentIntentStatus.EXPIRED;
            case "charge:pending" -> confirmations >= requiredConfirmations
                    ? PaymentIntentStatus.SUCCEEDED
                    : null;
            default -> null;
        };

        if (outcome == null && "charge:pending".equals(type)) {
            log.info("Crypto deposit {} seen with {}/{} confirmations, not crediting yet",
                     data.path("id").asString(), confirmations, requiredConfirmations);
        }

        return new ProviderEvent(
                event.path("id").asString(),
                type,
                data.path("id").asString(),
                outcome,
                data.path("failure_reason").asString(null));
    }

    private JsonNode post(String path, String body) {
        if (!isConfigured()) {
            throw new ProviderUnavailableException(
                    "The crypto gateway is not configured: set funding.crypto.api-url and api-key");
        }
        try {
            String response = RestClient.builder().baseUrl(apiUrl).build()
                    .post()
                    .uri(path)
                    .header("X-CC-Api-Key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(response);
        } catch (RestClientException ex) {
            throw new ProviderUnavailableException(
                    "Crypto gateway " + path + " failed: " + ex.getMessage(), ex);
        }
    }
}
