package com.example.admin.application.event;

import com.example.admin.application.Outcome;
import com.example.admin.application.util.AdminPiiMaskingUtils;
import com.example.messaging.outbox.OutboxWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes {@code admin.action.performed} events to the shared outbox in the
 * canonical envelope defined by
 * {@code specs/services/admin-service/data-model.md}:
 *
 * <pre>
 * {
 *   "eventId":   "UUID",
 *   "occurredAt":"ISO-8601 UTC ms",
 *   "actor":     {"type":"operator", "id": operatorId, "sessionId": jti},
 *   "action":    {"permission": key, "endpoint": uri, "method": httpMethod},
 *   "target":    {"type": ACCOUNT|SESSION|AUDIT_QUERY|..., "id": id, "displayHint": masked|null},
 *   "outcome":   SUCCESS|FAILURE|DENIED,
 *   "reason":    detail|null
 * }
 * </pre>
 *
 * <p>{@code target.displayHint} is produced centrally here via
 * {@link AdminPiiMaskingUtils} when the target is an ACCOUNT identifier. All
 * other target types emit a {@code null} displayHint so the consumer never
 * receives raw PII (rules/traits/regulated.md R4).
 */
@Component
@RequiredArgsConstructor
public class AdminEventPublisher {

    private static final String AGGREGATE_TYPE = "AdminAction";
    private static final String EVENT_TYPE = "admin.action.performed";

    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;

    public void publishAdminActionPerformed(Envelope env) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("occurredAt", env.occurredAt() == null ? null : env.occurredAt().toString());

        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("type", "operator");
        actor.put("id", env.operatorId());
        actor.put("sessionId", env.sessionId());
        payload.put("actor", actor);

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("permission", env.permission());
        action.put("endpoint", env.endpoint());
        action.put("method", env.method());
        payload.put("action", action);

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("type", env.targetType());
        target.put("id", env.targetId());
        target.put("displayHint", displayHintFor(env.targetType(), env.targetId()));
        payload.put("target", target);

        payload.put("outcome", env.outcome() == null ? null : env.outcome().name());
        payload.put("reason", env.reason());

        try {
            String json = objectMapper.writeValueAsString(payload);
            String aggregateId = env.targetId() != null ? env.targetId() : "-";
            outboxWriter.save(AGGREGATE_TYPE, aggregateId, EVENT_TYPE, json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize admin.action.performed event", e);
        }
    }

    private static String displayHintFor(String targetType, String targetId) {
        if (targetType == null || targetId == null) return null;
        if (!"ACCOUNT".equals(targetType)) return null;
        if (targetId.contains("@")) return AdminPiiMaskingUtils.maskEmail(targetId);
        // UUID / numeric account ids are not PII; do not leak them into displayHint.
        return null;
    }

    /**
     * Canonical input record for {@link #publishAdminActionPerformed(Envelope)}.
     * Keeps the publisher decoupled from {@code AdminActionAuditor}'s records.
     */
    public record Envelope(
            String operatorId,
            String sessionId,
            String permission,
            String endpoint,
            String method,
            String targetType,
            String targetId,
            Outcome outcome,
            String reason,
            Instant occurredAt
    ) {}
}
