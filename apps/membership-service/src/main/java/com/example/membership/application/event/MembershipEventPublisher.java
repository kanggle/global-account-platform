package com.example.membership.application.event;

import com.example.membership.domain.subscription.Subscription;
import com.example.messaging.outbox.OutboxWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class MembershipEventPublisher {

    private static final String AGGREGATE_TYPE = "membership";
    private static final String SOURCE = "membership-service";

    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;

    public void publishActivated(Subscription s) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subscriptionId", s.getId());
        payload.put("accountId", s.getAccountId());
        payload.put("planLevel", s.getPlanLevel().name());
        payload.put("startedAt", toInstant(s.getStartedAt()));
        payload.put("expiresAt", toInstant(s.getExpiresAt()));
        writeEvent("membership.subscription.activated", s.getAccountId(), payload);
    }

    public void publishExpired(Subscription s) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subscriptionId", s.getId());
        payload.put("accountId", s.getAccountId());
        payload.put("planLevel", s.getPlanLevel().name());
        payload.put("expiredAt", toInstant(s.getExpiresAt()));
        writeEvent("membership.subscription.expired", s.getAccountId(), payload);
    }

    public void publishCancelled(Subscription s) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("subscriptionId", s.getId());
        payload.put("accountId", s.getAccountId());
        payload.put("planLevel", s.getPlanLevel().name());
        payload.put("cancelledAt", toInstant(s.getCancelledAt()));
        writeEvent("membership.subscription.cancelled", s.getAccountId(), payload);
    }

    private String toInstant(LocalDateTime ldt) {
        return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC).toString();
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
            log.error("Failed to serialize membership event payload for {}: {}", eventType, e.getMessage());
        }
    }

}
