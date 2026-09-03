package com.moataz.paymentwallet.funding.provider;

import com.moataz.paymentwallet.funding.PaymentIntentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In this package so the test can stub {@code verifySignature}, which is a round trip to PayPal
 * rather than an HMAC and is not what these tests are about.
 *
 * PayPal splits paying in two: the buyer approves, the merchant captures. An order that is
 * approved and never captured expires with the customer believing they paid, so the approval
 * event has to trigger the capture even though it settles nothing itself.
 */
class PayPalEventMappingTest {

    private final List<String> captured = new ArrayList<>();

    private final PayPalAdapter adapter = new PayPalAdapter(JsonMapper.builder().build()) {
        @Override
        void verifySignature(String rawBody, Map<String, String> headers) {
        }

        @Override
        public void captureIfNeeded(ProviderEvent event) {
            if ("CHECKOUT.ORDER.APPROVED".equals(event.eventType())) {
                captured.add(event.providerReference());
            }
        }
    };

    @Test
    @DisplayName("an approved order settles nothing, but does ask to be captured")
    void approvalIsNotPayment() {
        String body = """
                {"id":"WH_1","event_type":"CHECKOUT.ORDER.APPROVED","resource":{"id":"5O190127TN364715T"}}
                """;

        ProviderEvent event = adapter.readEvent(body, Map.of());

        assertThat(event.outcome()).isNull();
        assertThat(event.providerReference()).isEqualTo("5O190127TN364715T");

        adapter.captureIfNeeded(event);
        assertThat(captured).containsExactly("5O190127TN364715T");
    }

    @Test
    @DisplayName("the capture is what reaches the ledger, and it names the order, not the capture")
    void captureSettles() {
        String body = """
                {"id":"WH_2","event_type":"PAYMENT.CAPTURE.COMPLETED",
                 "resource":{"id":"CAPTURE_9",
                             "supplementary_data":{"related_ids":{"order_id":"5O190127TN364715T"}}}}
                """;

        ProviderEvent event = adapter.readEvent(body, Map.of());

        assertThat(event.outcome()).isEqualTo(PaymentIntentStatus.SUCCEEDED);
        // the wallet stored the order id, so a capture that named only itself would never be found
        assertThat(event.providerReference()).isEqualTo("5O190127TN364715T");
        assertThat(captured).isEmpty();
    }

    @Test
    @DisplayName("a denied capture fails the payment")
    void deniedCaptureFails() {
        String body = """
                {"id":"WH_3","event_type":"PAYMENT.CAPTURE.DENIED",
                 "resource":{"id":"CAPTURE_9","status_details":{"reason":"INSTRUMENT_DECLINED"},
                             "supplementary_data":{"related_ids":{"order_id":"5O1"}}}}
                """;

        ProviderEvent event = adapter.readEvent(body, Map.of());

        assertThat(event.outcome()).isEqualTo(PaymentIntentStatus.FAILED);
        assertThat(event.failureReason()).isEqualTo("INSTRUMENT_DECLINED");
    }
}
