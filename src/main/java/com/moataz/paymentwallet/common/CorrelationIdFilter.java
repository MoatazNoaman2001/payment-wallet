package com.moataz.paymentwallet.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Stamps one id on every log line produced while handling a request.
 *
 * Without it, two concurrent transfers interleave in the log and there is no way to tell
 * which line belongs to which request — the logs are useless exactly when you need them,
 * which is under load. The id is echoed back in a response header so a client can quote
 * it in a bug report, and reused from an incoming X-Request-Id so it survives a hop from
 * a gateway or another service.
 *
 * MDC is a thread-local map that the logging pattern reads via %X{requestId}, so it must
 * be cleared in a finally block: servlet threads are pooled and reused.
 */
@Component
@Order(1)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = (incoming == null || incoming.isBlank())
                ? UUID.randomUUID().toString().substring(0, 8)
                : incoming.substring(0, Math.min(incoming.length(), 36));

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
