package com.moataz.paymentwallet.funding;

import com.moataz.paymentwallet.funding.provider.WebhookVerificationException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Where providers tell this wallet that money moved.
 *
 * Unauthenticated by necessity — Stripe holds no session — which is exactly why the signature
 * is the authentication. The endpoint takes the body as a raw String rather than a parsed
 * object: signatures are computed over the exact bytes sent, and a round trip through a JSON
 * parser and back reorders keys and drops whitespace, which breaks the very signature that
 * makes the request trustworthy.
 */
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Tag(name = "Webhooks", description = "Provider callbacks. Authenticated by signature, not by session")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookService webhookService;

    @Operation(summary = "Receive a provider callback")
    @PostMapping("/{provider}")
    public Map<String, String> receive(@PathVariable PaymentProvider provider,
                                       @RequestBody String rawBody,
                                       @RequestHeader Map<String, String> headers) {
        String outcome = webhookService.handle(provider, rawBody, lowercaseKeys(headers));
        return Map.of("status", outcome);
    }

    /**
     * A rejected signature is answered with 400 and nothing else. Saying which check failed
     * would help an attacker tune the next attempt.
     */
    @ExceptionHandler(WebhookVerificationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleVerification(WebhookVerificationException ex) {
        log.warn("Rejected a webhook: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Webhook rejected");
        problem.setDetail("Signature verification failed");
        return problem;
    }

    private static Map<String, String> lowercaseKeys(Map<String, String> headers) {
        Map<String, String> normalised = new LinkedHashMap<>();
        headers.forEach((key, value) -> normalised.put(key.toLowerCase(Locale.ROOT), value));
        return normalised;
    }
}
