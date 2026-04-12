package com.example.auth.application.event;

import com.example.auth.domain.session.SessionContext;
import com.example.messaging.outbox.OutboxWriter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthEventPublisherTest {

    @Mock
    private OutboxWriter outboxWriter;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @InjectMocks
    private AuthEventPublisher authEventPublisher;

    private static final String ACCOUNT_ID = "acc-123";

    @Test
    @DisplayName("publishTokenReuseDetected writes correct payload to outbox")
    void publishTokenReuseDetected_correctPayload() throws Exception {
        // given
        String reusedJti = "reused-jti-001";
        Instant originalRotationAt = Instant.parse("2026-04-12T09:50:00Z");
        Instant reuseAttemptAt = Instant.parse("2026-04-12T10:00:00Z");
        String ipMasked = "192.168.1.***";
        String deviceFingerprint = "fp-abc";
        boolean sessionsRevoked = true;
        int revokedCount = 5;

        // when
        authEventPublisher.publishTokenReuseDetected(
                ACCOUNT_ID, reusedJti, originalRotationAt, reuseAttemptAt,
                ipMasked, deviceFingerprint, sessionsRevoked, revokedCount);

        // then
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxWriter).save(eq("auth"), eq(ACCOUNT_ID),
                eq("auth.token.reuse.detected"), payloadCaptor.capture());

        Map<String, Object> envelope = objectMapper.readValue(
                payloadCaptor.getValue(), new TypeReference<>() {});

        assertThat(envelope).containsEntry("eventType", "auth.token.reuse.detected");
        assertThat(envelope).containsEntry("source", "auth-service");
        assertThat(envelope).containsEntry("schemaVersion", 1);
        assertThat(envelope).containsEntry("partitionKey", ACCOUNT_ID);
        assertThat(envelope).containsKey("eventId");
        assertThat(envelope).containsKey("occurredAt");

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        assertThat(payload).containsEntry("accountId", ACCOUNT_ID);
        assertThat(payload).containsEntry("reusedJti", reusedJti);
        assertThat(payload).containsEntry("originalRotationAt", originalRotationAt.toString());
        assertThat(payload).containsEntry("reuseAttemptAt", reuseAttemptAt.toString());
        assertThat(payload).containsEntry("ipMasked", ipMasked);
        assertThat(payload).containsEntry("deviceFingerprint", deviceFingerprint);
        assertThat(payload).containsEntry("sessionsRevoked", true);
        assertThat(payload).containsEntry("revokedCount", 5);
    }

    @Test
    @DisplayName("publishTokenReuseDetected handles null originalRotationAt")
    void publishTokenReuseDetected_nullOriginalRotationAt() throws Exception {
        // when
        authEventPublisher.publishTokenReuseDetected(
                ACCOUNT_ID, "jti-001", null, Instant.now(),
                "10.0.0.***", "fp-x", true, 3);

        // then
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxWriter).save(eq("auth"), eq(ACCOUNT_ID),
                eq("auth.token.reuse.detected"), payloadCaptor.capture());

        Map<String, Object> envelope = objectMapper.readValue(
                payloadCaptor.getValue(), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        assertThat(payload.get("originalRotationAt")).isNull();
    }

    @Test
    @DisplayName("publishSessionRevoked writes correct payload to outbox")
    void publishSessionRevoked_correctPayload() throws Exception {
        // given
        List<String> revokedJtis = List.of("jti-1", "jti-2", "jti-3");
        Instant revokedAt = Instant.parse("2026-04-12T10:00:00Z");

        // when
        authEventPublisher.publishSessionRevoked(
                ACCOUNT_ID, revokedJtis, "USER_LOGOUT", "user", null, revokedAt, 3);

        // then
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxWriter).save(eq("auth"), eq(ACCOUNT_ID),
                eq("session.revoked"), payloadCaptor.capture());

        Map<String, Object> envelope = objectMapper.readValue(
                payloadCaptor.getValue(), new TypeReference<>() {});

        assertThat(envelope).containsEntry("eventType", "session.revoked");
        assertThat(envelope).containsEntry("source", "auth-service");
        assertThat(envelope).containsEntry("schemaVersion", 1);
        assertThat(envelope).containsEntry("partitionKey", ACCOUNT_ID);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        assertThat(payload).containsEntry("accountId", ACCOUNT_ID);
        assertThat(payload).containsEntry("revokedJtis", revokedJtis);
        assertThat(payload).containsEntry("revokeReason", "USER_LOGOUT");
        assertThat(payload).containsEntry("actorType", "user");
        assertThat(payload.get("actorId")).isNull();
        assertThat(payload).containsEntry("revokedAt", revokedAt.toString());
        assertThat(payload).containsEntry("totalRevoked", 3);
    }

    @Test
    @DisplayName("publishLoginAttempted includes geoCountry in payload")
    void publishLoginAttempted_includesGeoCountry() throws Exception {
        // given
        SessionContext ctx = new SessionContext("127.0.0.1", "Chrome/120", "fp-123", "KR");

        // when
        authEventPublisher.publishLoginAttempted(ACCOUNT_ID, "email-hash", ctx);

        // then
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxWriter).save(eq("auth"), eq(ACCOUNT_ID),
                eq("auth.login.attempted"), payloadCaptor.capture());

        Map<String, Object> envelope = objectMapper.readValue(
                payloadCaptor.getValue(), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        assertThat(payload).containsEntry("geoCountry", "KR");
    }

    @Test
    @DisplayName("publishLoginFailed includes geoCountry in payload")
    void publishLoginFailed_includesGeoCountry() throws Exception {
        // given
        SessionContext ctx = new SessionContext("127.0.0.1", "Chrome/120", "fp-123", "US");

        // when
        authEventPublisher.publishLoginFailed(ACCOUNT_ID, "email-hash", "CREDENTIALS_INVALID", 3, ctx);

        // then
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxWriter).save(eq("auth"), eq(ACCOUNT_ID),
                eq("auth.login.failed"), payloadCaptor.capture());

        Map<String, Object> envelope = objectMapper.readValue(
                payloadCaptor.getValue(), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        assertThat(payload).containsEntry("geoCountry", "US");
    }

    @Test
    @DisplayName("publishLoginSucceeded includes geoCountry in payload")
    void publishLoginSucceeded_includesGeoCountry() throws Exception {
        // given
        SessionContext ctx = new SessionContext("127.0.0.1", "Chrome/120", "fp-123", "JP");

        // when
        authEventPublisher.publishLoginSucceeded(ACCOUNT_ID, "jti-001", ctx);

        // then
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxWriter).save(eq("auth"), eq(ACCOUNT_ID),
                eq("auth.login.succeeded"), payloadCaptor.capture());

        Map<String, Object> envelope = objectMapper.readValue(
                payloadCaptor.getValue(), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        assertThat(payload).containsEntry("geoCountry", "JP");
    }

    @Test
    @DisplayName("geoCountry defaults to XX when not provided")
    void geoCountryDefaultsToXX() throws Exception {
        // given - using 3-arg constructor which defaults geoCountry to "XX"
        SessionContext ctx = new SessionContext("127.0.0.1", "Chrome/120", "fp-123");

        // when
        authEventPublisher.publishLoginAttempted(ACCOUNT_ID, "email-hash", ctx);

        // then
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxWriter).save(eq("auth"), eq(ACCOUNT_ID),
                eq("auth.login.attempted"), payloadCaptor.capture());

        Map<String, Object> envelope = objectMapper.readValue(
                payloadCaptor.getValue(), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        assertThat(payload).containsEntry("geoCountry", "XX");
    }
}
