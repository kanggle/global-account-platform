package com.example.admin.application.event;

import com.example.admin.application.AdminActionAuditor.AuditRecord;
import com.example.messaging.outbox.OutboxWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes {@code admin.action.performed} events via the shared outbox writer.
 * Called from within the auditor's transaction so the outbox row commits atomically
 * with the audit row (A7 fail-closed).
 */
@Component
@RequiredArgsConstructor
public class AdminEventPublisher {

    private static final String AGGREGATE_TYPE = "AdminAction";
    private static final String EVENT_TYPE = "admin.action.performed";

    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;

    public void publishActionPerformed(AuditRecord record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("auditId", record.auditId());
        payload.put("actionCode", record.actionCode().name());

        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("type", "operator");
        actor.put("id", record.operator().operatorId());
        actor.put("role", primaryRoleName(record));
        payload.put("actor", actor);

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("type", record.targetType());
        target.put("id", record.targetId());
        payload.put("target", target);

        payload.put("reason", record.reason());
        payload.put("ticketId", record.ticketId());
        payload.put("outcome", record.outcome().name());
        payload.put("failureDetail", record.downstreamDetail());
        payload.put("startedAt", record.startedAt().toString());
        payload.put("completedAt", record.completedAt() == null ? null : record.completedAt().toString());

        try {
            String json = objectMapper.writeValueAsString(payload);
            outboxWriter.save(AGGREGATE_TYPE, record.targetId(), EVENT_TYPE, json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize admin.action.performed event", e);
        }
    }

    private static String primaryRoleName(AuditRecord record) {
        return record.operator().roles().stream()
                .findFirst()
                .map(Enum::name)
                .orElse("UNKNOWN");
    }
}
