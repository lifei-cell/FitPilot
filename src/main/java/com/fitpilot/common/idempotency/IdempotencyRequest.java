package com.fitpilot.common.idempotency;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Request-scoped idempotency metadata calculated by the HTTP filter and consumed by
 * critical application services that also provide database-backed idempotency.
 */
public record IdempotencyRequest(String key, String fingerprint) {
    public static final String ATTRIBUTE = IdempotencyRequest.class.getName();

    public static IdempotencyRequest from(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof IdempotencyRequest metadata ? metadata : null;
    }
}
