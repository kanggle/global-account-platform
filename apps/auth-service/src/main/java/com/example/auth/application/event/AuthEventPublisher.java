package com.example.auth.application.event;

import com.example.auth.domain.session.SessionContext;
import com.example.messaging.outbox.OutboxWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuthEventPublisher {

    private static final String AGGREGATE_TYPE = "auth";
    private static final String SOURCE = "auth-service";

    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;

    public void publishLoginAttempted(String accountId, String emailHash, SessionContext ctx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", accountId);
        payload.put("emailHash", emailHash);
        payload.put("ipMasked", ctx.ipMasked());
        payload.put("userAgentFamily", ctx.userAgentFamily());
        payload.put("deviceFingerprint", ctx.deviceFingerprint());
        payload.put("timestamp", Instant.now().toString());

        writeEvent("auth.login.attempted", accountId != null ? accountId : emailHash, payload);
    }

    public void publishLoginFailed(String accountId, String emailHash, String failureReason,
                                    int failCount, SessionContext ctx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", accountId);
        payload.put("emailHash", emailHash);
        payload.put("failureReason", failureReason);
        payload.put("failCount", failCount);
        payload.put("ipMasked", ctx.ipMasked());
        payload.put("userAgentFamily", ctx.userAgentFamily());
        payload.put("deviceFingerprint", ctx.deviceFingerprint());
        payload.put("timestamp", Instant.now().toString());

        writeEvent("auth.login.failed", accountId != null ? accountId : emailHash, payload);
    }

    public void publishLoginSucceeded(String accountId, String sessionJti, SessionContext ctx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", accountId);
        payload.put("ipMasked", ctx.ipMasked());
        payload.put("userAgentFamily", ctx.userAgentFamily());
        payload.put("deviceFingerprint", ctx.deviceFingerprint());
        payload.put("sessionJti", sessionJti);
        payload.put("timestamp", Instant.now().toString());

        writeEvent("auth.login.succeeded", accountId, payload);
    }

    public void publishTokenRefreshed(String accountId, String previousJti, String newJti,
                                       SessionContext ctx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", accountId);
        payload.put("previousJti", previousJti);
        payload.put("newJti", newJti);
        payload.put("ipMasked", ctx.ipMasked());
        payload.put("deviceFingerprint", ctx.deviceFingerprint());
        payload.put("timestamp", Instant.now().toString());

        writeEvent("auth.token.refreshed", accountId, payload);
    }

    private void writeEvent(String eventType, String aggregateId, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", eventType);
        envelope.put("source", SOURCE);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("schemaVersion", 1);
        envelope.put("partitionKey", aggregateId);
        envelope.put("payload", payload);

        try {
            String json = objectMapper.writeValueAsString(envelope);
            outboxWriter.save(AGGREGATE_TYPE, aggregateId, eventType, json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event payload for {}: {}", eventType, e.getMessage());
        }
    }
}
