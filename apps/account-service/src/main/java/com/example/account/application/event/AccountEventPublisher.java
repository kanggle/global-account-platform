package com.example.account.application.event;

import com.example.messaging.outbox.OutboxWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
                "emailHash", sha256Short(email),
                "status", status,
                "locale", locale,
                "createdAt", createdAt.toString()
        );
        outboxWriter.save("Account", accountId, "account.created", toJson(payload));
    }

    public void publishStatusChanged(String accountId, String previousStatus, String currentStatus,
                                      String reasonCode, String actorType, String actorId,
                                      Instant occurredAt) {
        Map<String, Object> payload = new java.util.HashMap<>(Map.of(
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
        Map<String, Object> payload = new java.util.HashMap<>(Map.of(
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
        Map<String, Object> payload = new java.util.HashMap<>(Map.of(
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
        Map<String, Object> payload = new java.util.HashMap<>(Map.of(
                "accountId", accountId,
                "reasonCode", reasonCode,
                "actorType", actorType,
                "deletedAt", deletedAt.toString(),
                "gracePeriodEndsAt", gracePeriodEndsAt.toString(),
                "anonymized", false
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

    private String sha256Short(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 10);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
