package com.moataz.paymentwallet;

import com.moataz.paymentwallet.funding.PaymentIntentStatus;
import com.moataz.paymentwallet.funding.provider.CryptoAdapter;
import com.moataz.paymentwallet.funding.provider.Hmac;
import com.moataz.paymentwallet.funding.provider.Money;
import com.moataz.paymentwallet.funding.provider.ProviderEvent;
import com.moataz.paymentwallet.funding.provider.StripeAdapter;
import com.moataz.paymentwallet.funding.provider.WebhookVerificationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Signature verification needs no API key and no network, so unlike the rest of the provider
 * code it can be tested properly. These are plain unit tests: no Spring context, no database.
 */
class WebhookSignatureTest {

    private static final String STRIPE_SECRET = "whsec_test_secret";
    private static final String CRYPTO_SECRET = "crypto_test_secret";

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    // ---------- Stripe ----------

    @Test
    @DisplayName("a correctly signed Stripe webhook is accepted and reduced to an outcome")
    void acceptsAValidStripeSignature() {
        StripeAdapter adapter = stripe();
        String body = """
                {"id":"evt_1","type":"checkout.session.completed",
                 "data":{"object":{"id":"cs_test_123"}}}""";

        ProviderEvent event = adapter.readEvent(body, Map.of("stripe-signature", stripeHeader(body)));

        assertThat(event.eventId()).isEqualTo("evt_1");
        assertThat(event.providerReference()).isEqualTo("cs_test_123");
        assertThat(event.outcome()).isEqualTo(PaymentIntentStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("a signature from the wrong secret is rejected")
    void rejectsAForgedSignature() {
        StripeAdapter adapter = stripe();
        String body = """
                {"id":"evt_1","type":"checkout.session.completed","data":{"object":{"id":"cs_1"}}}""";

        long now = Instant.now().getEpochSecond();
        String forged = "t=" + now + ",v1=" + Hmac.sha256Hex("whsec_attacker", now + "." + body);

        assertThatThrownBy(() -> adapter.readEvent(body, Map.of("stripe-signature", forged)))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    @DisplayName("a signature valid for a different body is rejected")
    void rejectsATamperedBody() {
        StripeAdapter adapter = stripe();
        String signed = """
                {"id":"evt_1","type":"checkout.session.completed","data":{"object":{"id":"cs_1"}}}""";
        String tampered = signed.replace("cs_1", "cs_2");

        String header = stripeHeader(signed);

        assertThatThrownBy(() -> adapter.readEvent(tampered, Map.of("stripe-signature", header)))
                .isInstanceOf(WebhookVerificationException.class);
    }

    @Test
    @DisplayName("a correctly signed but stale webhook is rejected, so a capture cannot be replayed")
    void rejectsAStaleSignature() {
        StripeAdapter adapter = stripe();
        String body = """
                {"id":"evt_1","type":"checkout.session.completed","data":{"object":{"id":"cs_1"}}}""";

        long lastWeek = Instant.now().getEpochSecond() - 7 * 24 * 3600;
        String header = "t=" + lastWeek + ",v1=" + Hmac.sha256Hex(STRIPE_SECRET, lastWeek + "." + body);

        assertThatThrownBy(() -> adapter.readEvent(body, Map.of("stripe-signature", header)))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("outside the replay tolerance");
    }

    @Test
    @DisplayName("with no webhook secret configured, nothing is trusted")
    void refusesToVerifyWithoutASecret() {
        StripeAdapter adapter = new StripeAdapter(objectMapper);
        ReflectionTestUtils.setField(adapter, "webhookSecret", "");

        assertThatThrownBy(() -> adapter.readEvent("{}", Map.of("stripe-signature", "t=1,v1=x")))
                .isInstanceOf(WebhookVerificationException.class)
                .hasMessageContaining("No Stripe webhook secret");
    }

    // ---------- crypto ----------

    @Test
    @DisplayName("a crypto deposit short of its confirmations credits nothing")
    void waitsForConfirmations() {
        CryptoAdapter adapter = crypto(3);
        String body = """
                {"event":{"id":"evt_9","type":"charge:pending",
                          "data":{"id":"ch_1","confirmations":1}}}""";

        ProviderEvent event = adapter.readEvent(body, Map.of(
                "x-cc-webhook-signature", Hmac.sha256Hex(CRYPTO_SECRET, body)));

        assertThat(event.providerReference()).isEqualTo("ch_1");
        assertThat(event.outcome()).isNull();
    }

    @Test
    @DisplayName("the same deposit settles once it is deep enough in the chain")
    void settlesAtTheRequiredDepth() {
        CryptoAdapter adapter = crypto(3);
        String body = """
                {"event":{"id":"evt_9","type":"charge:pending",
                          "data":{"id":"ch_1","confirmations":3}}}""";

        ProviderEvent event = adapter.readEvent(body, Map.of(
                "x-cc-webhook-signature", Hmac.sha256Hex(CRYPTO_SECRET, body)));

        assertThat(event.outcome()).isEqualTo(PaymentIntentStatus.SUCCEEDED);
    }

    // ---------- money ----------

    @Test
    @DisplayName("amounts convert exactly to a provider's minor units")
    void convertsMoneyExactly() {
        assertThat(Money.toMinorUnits(new BigDecimal("1600.0000"), 2)).isEqualTo(160000L);
        assertThat(Money.toDecimalString(new BigDecimal("1600.0000"), 2)).isEqualTo("1600.00");
        assertThat(Money.fromMinorUnits(160000L, 2)).isEqualByComparingTo("1600.00");
    }

    @Test
    @DisplayName("an amount that would have to be rounded is refused, not rounded")
    void refusesToRoundMoney() {
        assertThatThrownBy(() -> Money.toMinorUnits(new BigDecimal("10.5050"), 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refusing to round money");
    }

    // ---------- fixtures ----------

    private StripeAdapter stripe() {
        StripeAdapter adapter = new StripeAdapter(objectMapper);
        ReflectionTestUtils.setField(adapter, "webhookSecret", STRIPE_SECRET);
        return adapter;
    }

    private CryptoAdapter crypto(int confirmations) {
        CryptoAdapter adapter = new CryptoAdapter(objectMapper);
        ReflectionTestUtils.setField(adapter, "webhookSecret", CRYPTO_SECRET);
        ReflectionTestUtils.setField(adapter, "requiredConfirmations", confirmations);
        return adapter;
    }

    private static String stripeHeader(String body) {
        long now = Instant.now().getEpochSecond();
        return "t=" + now + ",v1=" + Hmac.sha256Hex(STRIPE_SECRET, now + "." + body);
    }
}
