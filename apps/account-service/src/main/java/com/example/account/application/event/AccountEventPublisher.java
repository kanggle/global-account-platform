package com.example.account.application.event;

import com.example.account.application.util.DigestUtils;
import com.example.common.id.UuidV7;
import com.example.messaging.outbox.OutboxWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class AccountEventPublisher {

    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;

    public void publishAccountCreated(String accountId, String email, String status,
                                       String locale, Instant createdAt) {
        Map<String, Object> payload = Map.of(
                "accountId", accountId,
                "emailHash", DigestUtils.sha256Short(email, 10),
                "status", status,
                "locale", locale,
                "createdAt", createdAt.toString()
        );
        outboxWriter.save("Account", accountId, "account.created", toJson(payload));
    }

    public void publishStatusChanged(String accountId, String previousStatus, String currentStatus,
                                      String reasonCode, String actorType, String actorId,
                                      Instant occurredAt) {
        Map<String, Object> payload = new HashMap<>(Map.of(
                "accountId", accountId,
                "previousStatus", previousStatus,
                "currentStatus", currentStatus,
                "reasonCode", reasonCode,
                "actorType", actorType,
                "occurredAt", occurredAt.toString()
        ));
        if (actorId != null) {
            payload.put("actorId", actorId);
        }
        outboxWriter.save("Account", accountId, "account.status.changed", toJson(payload));
    }

    public void publishAccountLocked(String accountId, String reasonCode,
                                      String actorType, String actorId, Instant lockedAt) {
        // TASK-BE-041b-fix Critical 1: include eventId (UUID v7) in the flat payload so
        // security-service's AccountLockedConsumer can idempotently deduplicate replays
        // via the account_lock_history.event_id unique constraint. Without this field,
        // the consumer would synthesize a random UUID per delivery and insert duplicate
        // rows on Kafka at-least-once redelivery (contract: specs/contracts/events/account-events.md).
        Map<String, Object> payload = new HashMap<>(Map.of(
                "eventId", UuidV7.randomString(),
                "accountId", accountId,
                "reasonCode", reasonCode,
                "actorType", actorType,
                "lockedAt", lockedAt.toString()
        ));
        if (actorId != null) {
            payload.put("actorId", actorId);
        }
        outboxWriter.save("Account", accountId, "account.locked", toJson(payload));
    }

    public void publishAccountUnlocked(String accountId, String reasonCode,
                                        String actorType, String actorId, Instant unlockedAt) {
        Map<String, Object> payload = new HashMap<>(Map.of(
                "accountId", accountId,
                "reasonCode", reasonCode,
                "actorType", actorType,
                "unlockedAt", unlockedAt.toString()
        ));
        if (actorId != null) {
            payload.put("actorId", actorId);
        }
        outboxWriter.save("Account", accountId, "account.unlocked", toJson(payload));
    }

    public void publishAccountDeleted(String accountId, String reasonCode,
                                       String actorType, String actorId,
                                       Instant deletedAt, Instant gracePeriodEndsAt) {
        publishAccountDeletedEvent(accountId, reasonCode, actorType, actorId, deletedAt, gracePeriodEndsAt, false);
    }

    /**
     * Re-publish {@code account.deleted} with {@code anonymized=true} after grace-period
     * PII masking (retention.md §2.7).
     *
     * <p>Per <a href="../../../../../../../../specs/contracts/events/account-events.md">account-events.md</a>
     * the payload schema requires {@code gracePeriodEndsAt} (the original grace-period end,
     * i.e. {@code deletedAt + 30d}) — not the anonymization timestamp. The {@code deletedAt}
     * field carries the original DELETED transition time and must be preserved on re-publish.
     */
    public void publishAccountDeletedAnonymized(String accountId, String reasonCode,
                                                    String actorType, String actorId,
                                                    Instant deletedAt,
                                                    Instant gracePeriodEndsAt) {
        publishAccountDeletedEvent(accountId, reasonCode, actorType, actorId, deletedAt, gracePeriodEndsAt, true);
    }

    private void publishAccountDeletedEvent(String accountId, String reasonCode,
                                             String actorType, String actorId,
                                             Instant deletedAt, Instant gracePeriodEndsAt,
                                             boolean anonymized) {
        Map<String, Object> payload = new HashMap<>(Map.of(
                "accountId", accountId,
                "reasonCode", reasonCode,
                "actorType", actorType,
                "deletedAt", deletedAt.toString(),
                "gracePeriodEndsAt", gracePeriodEndsAt.toString(),
                "anonymized", anonymized
        ));
        if (actorId != null) {
            payload.put("actorId", actorId);
        }
        outboxWriter.save("Account", accountId, "account.deleted", toJson(payload));
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event payload", e);
        }
    }

}
