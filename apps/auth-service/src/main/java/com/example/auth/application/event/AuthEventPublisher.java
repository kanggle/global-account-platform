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
import java.util.List;
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
        payload.put("geoCountry", ctx.resolvedGeoCountry());
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
        payload.put("geoCountry", ctx.resolvedGeoCountry());
        payload.put("timestamp", Instant.now().toString());

        writeEvent("auth.login.failed", accountId != null ? accountId : emailHash, payload);
    }

    /**
     * Legacy 3-arg form kept for integration tests and any pre-TASK-BE-025 call site.
     * Emits a payload that <b>omits</b> {@code deviceId} and {@code isNewDevice} entirely
     * (field absence, not explicit nulls) so consumers correctly treat the event as legacy
     * and fall back to fingerprint logic via {@code JsonNode#isMissingNode()}. Built
     * independently of the 5-arg form to avoid leaking null keys into the JSON envelope
     * (TASK-BE-026 warning absorption from TASK-BE-025 review).
     */
    public void publishLoginSucceeded(String accountId, String sessionJti, SessionContext ctx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", accountId);
        payload.put("ipMasked", ctx.ipMasked());
        payload.put("userAgentFamily", ctx.userAgentFamily());
        payload.put("deviceFingerprint", ctx.deviceFingerprint());
        payload.put("geoCountry", ctx.resolvedGeoCountry());
        payload.put("sessionJti", sessionJti);
        payload.put("timestamp", Instant.now().toString());

        writeEvent("auth.login.succeeded", accountId, payload);
    }

    /**
     * Extended form (TASK-BE-025): carries {@code deviceId} and {@code isNewDevice} so
     * security-service's DeviceChangeRule can evaluate on the authoritative
     * device_sessions signal instead of fingerprint churn. Both fields are additive
     * (nullable) for backward-compat with legacy consumers.
     *
     * @param deviceId     device_sessions.device_id stamped on this login (nullable)
     * @param isNewDevice  true iff the device_sessions row was created in this login
     *                     transaction; false iff an existing active row was touched.
     *                     null signals "unknown" (consumers fall back to fingerprint).
     */
    public void publishLoginSucceeded(String accountId, String sessionJti, SessionContext ctx,
                                      String deviceId, Boolean isNewDevice) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", accountId);
        payload.put("ipMasked", ctx.ipMasked());
        payload.put("userAgentFamily", ctx.userAgentFamily());
        payload.put("deviceFingerprint", ctx.deviceFingerprint());
        payload.put("geoCountry", ctx.resolvedGeoCountry());
        payload.put("sessionJti", sessionJti);
        payload.put("deviceId", deviceId);
        payload.put("isNewDevice", isNewDevice);
        payload.put("timestamp", Instant.now().toString());

        writeEvent("auth.login.succeeded", accountId, payload);
    }

    /**
     * Extended form carrying {@code loginMethod} for OAuth social logins.
     * The {@code loginMethod} field is additive (nullable) for backward-compat.
     *
     * @param loginMethod e.g. "EMAIL_PASSWORD", "OAUTH_GOOGLE", "OAUTH_KAKAO"
     */
    public void publishLoginSucceeded(String accountId, String sessionJti, SessionContext ctx,
                                      String deviceId, Boolean isNewDevice, String loginMethod) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", accountId);
        payload.put("ipMasked", ctx.ipMasked());
        payload.put("userAgentFamily", ctx.userAgentFamily());
        payload.put("deviceFingerprint", ctx.deviceFingerprint());
        payload.put("geoCountry", ctx.resolvedGeoCountry());
        payload.put("sessionJti", sessionJti);
        payload.put("deviceId", deviceId);
        payload.put("isNewDevice", isNewDevice);
        payload.put("loginMethod", loginMethod);
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

    /**
     * Publishes auth.token.reuse.detected event when a previously rotated refresh token
     * is used again. This is a security-critical event.
     */
    public void publishTokenReuseDetected(String accountId, String reusedJti,
                                           Instant originalRotationAt, Instant reuseAttemptAt,
                                           String ipMasked, String deviceFingerprint,
                                           boolean sessionsRevoked, int revokedCount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", accountId);
        payload.put("reusedJti", reusedJti);
        payload.put("originalRotationAt", originalRotationAt != null ? originalRotationAt.toString() : null);
        payload.put("reuseAttemptAt", reuseAttemptAt.toString());
        payload.put("ipMasked", ipMasked);
        payload.put("deviceFingerprint", deviceFingerprint);
        payload.put("sessionsRevoked", sessionsRevoked);
        payload.put("revokedCount", revokedCount);

        writeEvent("auth.token.reuse.detected", accountId, payload);
    }

    /**
     * Publishes {@code auth.session.created} when a new device session is registered on
     * login. Spec: specs/contracts/events/auth-events.md.
     *
     * @param evictedDeviceIds device_ids that were evicted in the same transaction by the
     *                         concurrent-session limit; empty list if none
     */
    public void publishAuthSessionCreated(String accountId, String deviceId, String sessionJti,
                                          String deviceFingerprintHash, String userAgentFamily,
                                          String ipMasked, String geoCountry, Instant issuedAt,
                                          List<String> evictedDeviceIds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", accountId);
        payload.put("deviceId", deviceId);
        payload.put("sessionJti", sessionJti);
        payload.put("deviceFingerprintHash", deviceFingerprintHash);
        payload.put("userAgentFamily", userAgentFamily);
        payload.put("ipMasked", ipMasked);
        payload.put("geoCountry", geoCountry);
        payload.put("issuedAt", issuedAt.toString());
        payload.put("evictedDeviceIds", evictedDeviceIds != null ? evictedDeviceIds : List.of());

        writeEvent("auth.session.created", accountId, payload);
    }

    /**
     * Publishes {@code auth.session.revoked} for a single device session per the new
     * (TASK-BE-022) payload shape. Spec: specs/contracts/events/auth-events.md.
     *
     * @param reason       canonical {@code RevokeReason} name
     * @param revokedJtis  jtis of refresh_tokens flipped from active to revoked in this op
     * @param actorType    {@code USER | ADMIN | SYSTEM}
     * @param actorAccountId actor identifier (null for SYSTEM)
     */
    public void publishAuthSessionRevoked(String accountId, String deviceId, String reason,
                                          List<String> revokedJtis, Instant revokedAt,
                                          String actorType, String actorAccountId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", accountId);
        payload.put("deviceId", deviceId);
        payload.put("reason", reason);
        payload.put("revokedJtis", revokedJtis != null ? revokedJtis : List.of());
        payload.put("revokedAt", revokedAt.toString());

        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("type", actorType);
        actor.put("accountId", actorAccountId);
        payload.put("actor", actor);

        writeEvent("auth.session.revoked", accountId, payload);
    }

    private void writeEvent(String eventType, String aggregateId, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        // TODO: TASK-BE-015 switch to UUID v7 when Java 21+ UUID v7 support is added
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
