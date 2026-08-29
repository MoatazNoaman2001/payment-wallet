package com.moataz.paymentwallet.funding.provider;

import com.moataz.paymentwallet.funding.PaymentDirection;
import com.moataz.paymentwallet.funding.PaymentIntentStatus;
import com.moataz.paymentwallet.funding.PaymentProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A provider that is this application talking to itself.
 *
 * It exists so the whole flow — intent, redirect, signed webhook, ledger posting — can be
 * demonstrated and tested with no account anywhere and no network. It signs its webhooks the
 * same way the real ones do, so the verification path under test is the real verification path.
 */
@Component
@RequiredArgsConstructor
public class SandboxAdapter implements PaymentProviderAdapter {

    private final ObjectMapper objectMapper;

    @Value("${funding.base-url:}")
    private String baseUrl;

    @Value("${funding.sandbox.webhook-secret:sandbox-webhook-secret}")
    private String webhookSecret;

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.SANDBOX;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public Set<PaymentDirection> supports() {
        return Set.of(PaymentDirection.DEPOSIT, PaymentDirection.WITHDRAWAL);
    }

    @Override
    public ProviderHandle createCharge(PaymentContext payment) {
        String reference = "sbx_ch_" + UUID.randomUUID().toString().replace("-", "");
        return ProviderHandle.redirect(reference, baseUrl + "/funding/sandbox/" + payment.reference());
    }

    @Override
    public ProviderHandle createPayout(PaymentContext payment) {
        return ProviderHandle.pending("sbx_po_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @Override
    public ProviderEvent readEvent(String rawBody, Map<String, String> headers) {
        String signature = headers.get("x-sandbox-signature");
        if (!Hmac.matches(Hmac.sha256Hex(webhookSecret, rawBody), signature)) {
            throw new WebhookVerificationException("Sandbox webhook signature does not match");
        }

        JsonNode json = objectMapper.readTree(rawBody);
        String status = json.path("status").asString("");

        return new ProviderEvent(
                json.path("id").asString(),
                json.path("type").asString("sandbox.event"),
                json.path("reference").asString(),
                switch (status) {
                    case "succeeded" -> PaymentIntentStatus.SUCCEEDED;
                    case "failed" -> PaymentIntentStatus.FAILED;
                    case "cancelled" -> PaymentIntentStatus.CANCELLED;
                    default -> null;
                },
                json.path("reason").asString(null));
    }

    public String sign(String rawBody) {
        return Hmac.sha256Hex(webhookSecret, rawBody);
    }
}
