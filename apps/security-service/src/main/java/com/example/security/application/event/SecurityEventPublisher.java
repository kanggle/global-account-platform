package com.example.security.application.event;

import com.example.messaging.outbox.OutboxWriter;
import com.example.security.domain.detection.AccountLockClient;
import com.example.security.domain.suspicious.SuspiciousEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Outbox-based publisher for security-service Kafka events.
 *
 * <p>All events share the standard envelope declared in
 * {@code specs/contracts/events/auth-events.md} (eventId, eventType, source,
 * occurredAt, schemaVersion, partitionKey, payload).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityEventPublisher {

    public static final String TOPIC_SUSPICIOUS_DETECTED = "security.suspicious.detected";
    public static final String TOPIC_AUTO_LOCK_TRIGGERED = "security.auto.lock.triggered";
    public static final String TOPIC_AUTO_LOCK_PENDING = "security.auto.lock.pending";

    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;

    public void publishSuspiciousDetected(SuspiciousEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("suspiciousEventId", event.getId());
        payload.put("accountId", event.getAccountId());
        payload.put("ruleCode", event.getRuleCode());
        payload.put("riskScore", event.getRiskScore());
        payload.put("actionTaken", event.getActionTaken().name());
        payload.put("evidence", event.getEvidence());
        payload.put("triggerEventId", event.getTriggerEventId());
        payload.put("detectedAt", event.getDetectedAt().toString());
        writeEnvelope(TOPIC_SUSPICIOUS_DETECTED, event.getAccountId(), payload);
    }

    public void publishAutoLockTriggered(SuspiciousEvent event, AccountLockClient.Status status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("suspiciousEventId", event.getId());
        payload.put("accountId", event.getAccountId());
        payload.put("ruleCode", event.getRuleCode());
        payload.put("riskScore", event.getRiskScore());
        payload.put("lockRequestResult", mapStatus(status));
        payload.put("lockRequestedAt", Instant.now().toString());
        writeEnvelope(TOPIC_AUTO_LOCK_TRIGGERED, event.getAccountId(), payload);
    }

    /**
     * Emitted when all retries to account-service have been exhausted. Consumed
     * by the operator manual-intervention path.
     */
    public void publishAutoLockPending(SuspiciousEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("suspiciousEventId", event.getId());
        payload.put("accountId", event.getAccountId());
        payload.put("ruleCode", event.getRuleCode());
        payload.put("riskScore", event.getRiskScore());
        payload.put("reason", "ACCOUNT_SERVICE_UNREACHABLE");
        payload.put("raisedAt", Instant.now().toString());
        writeEnvelope(TOPIC_AUTO_LOCK_PENDING, event.getAccountId(), payload);
    }

    private String mapStatus(AccountLockClient.Status status) {
        return switch (status) {
            case SUCCESS -> "SUCCESS";
            case ALREADY_LOCKED -> "ALREADY_LOCKED";
            case INVALID_TRANSITION, FAILURE -> "FAILURE";
        };
    }

    private void writeEnvelope(String eventType, String partitionKey, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", eventType);
        envelope.put("source", "security-service");
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("schemaVersion", 1);
        envelope.put("partitionKey", partitionKey);
        envelope.put("payload", payload);

        try {
            String json = objectMapper.writeValueAsString(envelope);
            outboxWriter.save("security", partitionKey, eventType, json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize {} event for outbox; suspiciousEventId={}",
                    eventType, payload.get("suspiciousEventId"), e);
            throw new IllegalStateException("Outbox serialization failed", e);
        }
    }
}
