package com.example.security.consumer;

import com.example.security.domain.history.LoginHistoryEntry;
import com.example.security.domain.history.LoginOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AuthEventMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Maps auth.login.succeeded envelope to LoginHistoryEntry with SUCCESS outcome")
    void mapsSucceededEventToEntry() throws Exception {
        String json = """
                {
                  "eventId": "evt-001",
                  "eventType": "auth.login.succeeded",
                  "source": "auth-service",
                  "occurredAt": "2026-04-12T10:00:00Z",
                  "schemaVersion": 1,
                  "partitionKey": "acc-123",
                  "payload": {
                    "accountId": "acc-123",
                    "ipMasked": "192.168.1.***",
                    "userAgentFamily": "Chrome 120",
                    "deviceFingerprint": "abcdef123456789012345678",
                    "geoCountry": "KR",
                    "sessionJti": "jti-001",
                    "timestamp": "2026-04-12T10:00:00Z"
                  }
                }
                """;

        JsonNode envelope = objectMapper.readTree(json);
        LoginHistoryEntry entry = AuthEventMapper.toLoginHistoryEntry(envelope, LoginOutcome.SUCCESS);

        assertThat(entry.getEventId()).isEqualTo("evt-001");
        assertThat(entry.getAccountId()).isEqualTo("acc-123");
        assertThat(entry.getOutcome()).isEqualTo(LoginOutcome.SUCCESS);
        assertThat(entry.getIpMasked()).isEqualTo("192.168.1.***");
        assertThat(entry.getUserAgentFamily()).isEqualTo("Chrome 120");
        assertThat(entry.getDeviceFingerprint()).hasSize(12);
        assertThat(entry.getGeoCountry()).isEqualTo("KR");
        assertThat(entry.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("Maps auth.login.failed envelope with RATE_LIMITED reason")
    void mapsFailedRateLimitedEvent() throws Exception {
        String json = """
                {
                  "eventId": "evt-002",
                  "eventType": "auth.login.failed",
                  "source": "auth-service",
                  "occurredAt": "2026-04-12T10:00:00Z",
                  "schemaVersion": 1,
                  "partitionKey": "acc-456",
                  "payload": {
                    "accountId": "acc-456",
                    "emailHash": "abc123",
                    "failureReason": "RATE_LIMITED",
                    "failCount": 6,
                    "ipMasked": "10.0.0.***",
                    "userAgentFamily": "Safari 17",
                    "deviceFingerprint": "xyz789",
                    "geoCountry": "US",
                    "timestamp": "2026-04-12T10:00:00Z"
                  }
                }
                """;

        JsonNode envelope = objectMapper.readTree(json);
        LoginOutcome outcome = AuthEventMapper.resolveFailureOutcome(envelope.path("payload"));

        assertThat(outcome).isEqualTo(LoginOutcome.RATE_LIMITED);
    }

    @Test
    @DisplayName("resolveFailureOutcome returns FAILURE for non-RATE_LIMITED reasons")
    void resolveFailureOutcomeNonRateLimited() throws Exception {
        String json = """
                { "failureReason": "CREDENTIALS_INVALID" }
                """;
        JsonNode payload = objectMapper.readTree(json);
        assertThat(AuthEventMapper.resolveFailureOutcome(payload)).isEqualTo(LoginOutcome.FAILURE);
    }

    @ParameterizedTest
    @CsvSource({
            "192.168.1.100, 192.168.1.***",
            "10.0.0.1, 10.0.0.***",
            "192.168.1.***, 192.168.1.***",
            "'', ''"
    })
    @DisplayName("maskIp replaces last octet with ***")
    void maskIpReplacesLastOctet(String input, String expected) {
        assertThat(AuthEventMapper.maskIp(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("maskIp returns null for null input")
    void maskIpNullInput() {
        assertThat(AuthEventMapper.maskIp(null)).isNull();
    }

    @Test
    @DisplayName("truncateFingerprint truncates to 12 chars")
    void truncateFingerprintLong() {
        assertThat(AuthEventMapper.truncateFingerprint("abcdef123456789012345678"))
                .isEqualTo("abcdef123456");
    }

    @Test
    @DisplayName("truncateFingerprint returns short strings unchanged")
    void truncateFingerprintShort() {
        assertThat(AuthEventMapper.truncateFingerprint("abc")).isEqualTo("abc");
    }

    @Test
    @DisplayName("truncateFingerprint returns null for null input")
    void truncateFingerprintNull() {
        assertThat(AuthEventMapper.truncateFingerprint(null)).isNull();
    }

    @Test
    @DisplayName("Maps event with null accountId")
    void mapsEventWithNullAccountId() throws Exception {
        String json = """
                {
                  "eventId": "evt-003",
                  "eventType": "auth.login.attempted",
                  "source": "auth-service",
                  "occurredAt": "2026-04-12T10:00:00Z",
                  "schemaVersion": 1,
                  "partitionKey": "hash-abc",
                  "payload": {
                    "accountId": null,
                    "emailHash": "hash-abc",
                    "ipMasked": "1.2.3.***",
                    "userAgentFamily": "Firefox 120",
                    "deviceFingerprint": "fp-short",
                    "geoCountry": "JP",
                    "timestamp": "2026-04-12T10:00:00Z"
                  }
                }
                """;

        JsonNode envelope = objectMapper.readTree(json);
        LoginHistoryEntry entry = AuthEventMapper.toLoginHistoryEntry(envelope, LoginOutcome.ATTEMPTED);

        assertThat(entry.getAccountId()).isNull();
        assertThat(entry.getOutcome()).isEqualTo(LoginOutcome.ATTEMPTED);
    }
}
