package com.example.auth.application.event;

import com.example.auth.domain.session.SessionContext;
import com.example.messaging.event.BaseEventPublisher;
import com.example.messaging.outbox.OutboxWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AuthEventPublisher extends BaseEventPublisher {

    private static final String AGGREGATE_TYPE = "auth";
    private static final String SOURCE = "auth-service";

    public AuthEventPublisher(OutboxWriter outboxWriter, ObjectMapper objectMapper) {
        super(outboxWriter, objectMapper);
    }

    public void publishLoginAttempted(String accountId, String emailHash, SessionContext ctx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", accountId);
        payload.put("emailHash", emailHash);
        payload.put("ipMasked", ctx.ipMasked());
        payload.put("userAgentFamily", ctx.userAgentFamily());
        payload.put("deviceFingerprint", ctx.deviceFingerprint());
        payload.put("geoCountry", ctx.resolvedGeoCountry());
        payload.put("timestamp", Instant.now().toString());

        write("auth.login.attempted", accountId != null ? accountId : emailHash, payload);
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

        write("auth.login.failed", accountId != null ? accountId : emailHash, payload);
    }

    /**
     * Legacy 3-arg form kept for integration tests and any pre-TASK-BE-025 call site.
     * Emits a payload that <b>omits</b> {@code deviceId} and {@code isNewDevice} entirely
     * (field absence, not explicit nulls) so consumers correctly treat the event as legacy
     * and fall back to fingerprint logic via {@code JsonNode#isMissingNode()}.
     * The shared {@link #buildLoginSucceededBase} helper is intentionally limited to the
     * 7 common fields and never inserts the additive TASK-BE-025/OAuth fields, preserving
     * the "no null keys" contract for legacy consumers.
     */
    public void publishLoginSucceeded(String accountId, String sessionJti, SessionContext ctx) {
        Map<String, Object> payload = buildLoginSucceededBase(accountId, sessionJti, ctx);

        write("auth.login.succeeded", accountId, payload);
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
        Map<String, Object> payload = buildLoginSucceededBase(accountId, sessionJti, ctx);
        // Preserve documented field order: deviceId/isNewDevice come BEFORE timestamp.
        Object timestamp = payload.remove("timestamp");
        payload.put("deviceId", deviceId);
        payload.put("isNewDevice", isNewDevice);
        payload.put("timestamp", timestamp);

        write("auth.login.succeeded", accountId, payload);
    }

    /**
     * Extended form carrying {@code loginMethod} for OAuth social logins.
     * The {@code loginMethod} field is additive (nullable) for backward-compat.
     *
     * @param loginMethod e.g. "EMAIL_PASSWORD", "OAUTH_GOOGLE", "OAUTH_KAKAO"
     */
    public void publishLoginSucceeded(String accountId, String sessionJti, SessionContext ctx,
                                      String deviceId, Boolean isNewDevice, String loginMethod) {
        Map<String, Object> payload = buildLoginSucceededBase(accountId, sessionJti, ctx);
        // Preserve documented field order: deviceId/isNewDevice/loginMethod BEFORE timestamp.
        Object timestamp = payload.remove("timestamp");
        payload.put("deviceId", deviceId);
        payload.put("isNewDevice", isNewDevice);
        payload.put("loginMethod", loginMethod);
        payload.put("timestamp", timestamp);

        write("auth.login.succeeded", accountId, payload);
    }

    /**
     * Builds the 7 common fields shared by every {@code publishLoginSucceeded} overload in
     * the documented insertion order: accountId, ipMasked, userAgentFamily,
     * deviceFingerprint, geoCountry, sessionJti, timestamp. Returned as a mutable
     * {@link LinkedHashMap} so callers can append additional fields (extended overloads
     * remove/re-insert {@code timestamp} to keep it last). Intentionally excludes
     * {@code deviceId}, {@code isNewDevice}, and {@code loginMethod} so that the legacy
     * 3-arg form never leaks null keys into the JSON envelope (TASK-BE-026 contract:
     * field absence, not explicit nulls).
     */
    private Map<String, Object> buildLoginSucceededBase(String accountId, String sessionJti,
                                                        SessionContext ctx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", accountId);
        payload.put("ipMasked", ctx.ipMasked());
        payload.put("userAgentFamily", ctx.userAgentFamily());
        payload.put("deviceFingerprint", ctx.deviceFingerprint());
        payload.put("geoCountry", ctx.resolvedGeoCountry());
        payload.put("sessionJti", sessionJti);
        payload.put("timestamp", Instant.now().toString());
        return payload;
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

        write("auth.token.refreshed", accountId, payload);
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

        write("auth.token.reuse.detected", accountId, payload);
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

        write("auth.session.created", accountId, payload);
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

        write("auth.session.revoked", accountId, payload);
    }

    private void write(String eventType, String aggregateId, Map<String, Object> payload) {
        // TODO: TASK-BE-015 switch to UUID v7 when Java 21+ UUID v7 support is added
        writeEvent(AGGREGATE_TYPE, aggregateId, eventType, SOURCE, payload);
    }
}
