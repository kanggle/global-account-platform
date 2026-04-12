package com.example.security.domain.detection;

import java.time.Instant;

/**
 * Context for rule evaluation. Derived from the consumed Kafka event.
 * Pure domain type — no framework imports.
 */
public record EvaluationContext(
        String eventId,
        String eventType,
        String accountId,
        String ipMasked,
        String deviceFingerprint,
        String geoCountry,
        Instant occurredAt,
        Integer failCount
) {

    public boolean hasAccount() {
        return accountId != null && !accountId.isBlank();
    }

    public boolean isLoginFailed() {
        return "auth.login.failed".equals(eventType);
    }

    public boolean isLoginSucceeded() {
        return "auth.login.succeeded".equals(eventType);
    }

    public boolean isTokenReuseDetected() {
        return "auth.token.reuse.detected".equals(eventType);
    }
}
