package com.example.admin.infrastructure.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Read-only queries to security-service for audit integration (login_history, suspicious_events).
 * Falls back to empty lists on failure so the integrated audit view remains available.
 */
@Slf4j
@Component
public class SecurityServiceClient {

    private final RestClient restClient;
    private final String internalToken;

    public SecurityServiceClient(
            @Value("${admin.security-service.base-url}") String baseUrl,
            @Value("${admin.downstream.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${admin.downstream.read-timeout-ms:10000}") int readTimeoutMs,
            @Value("${admin.downstream.internal-token:}") String internalToken) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        this.internalToken = internalToken;
    }

    public List<LoginHistoryEntry> queryLoginHistory(String accountId, Instant from, Instant to) {
        try {
            LoginHistoryResponse resp = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/security/login-history")
                            .queryParamIfPresent("accountId", java.util.Optional.ofNullable(accountId))
                            .queryParamIfPresent("from", java.util.Optional.ofNullable(from))
                            .queryParamIfPresent("to", java.util.Optional.ofNullable(to))
                            .build())
                    .headers(this::applyInternalToken)
                    .retrieve()
                    .body(LoginHistoryResponse.class);
            return resp == null || resp.content() == null ? Collections.emptyList() : resp.content();
        } catch (Exception e) {
            log.warn("security-service login-history query failed, returning empty", e);
            return Collections.emptyList();
        }
    }

    public List<SuspiciousEventEntry> querySuspiciousEvents(String accountId, Instant from, Instant to) {
        try {
            SuspiciousResponse resp = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/security/suspicious-events")
                            .queryParamIfPresent("accountId", java.util.Optional.ofNullable(accountId))
                            .queryParamIfPresent("from", java.util.Optional.ofNullable(from))
                            .queryParamIfPresent("to", java.util.Optional.ofNullable(to))
                            .build())
                    .headers(this::applyInternalToken)
                    .retrieve()
                    .body(SuspiciousResponse.class);
            return resp == null || resp.content() == null ? Collections.emptyList() : resp.content();
        } catch (Exception e) {
            log.warn("security-service suspicious-events query failed, returning empty", e);
            return Collections.emptyList();
        }
    }

    private void applyInternalToken(org.springframework.http.HttpHeaders h) {
        if (internalToken != null && !internalToken.isBlank()) {
            h.add("X-Internal-Token", internalToken);
        }
    }

    public record LoginHistoryEntry(
            String eventId,
            String accountId,
            String outcome,
            String ipMasked,
            String geoCountry,
            Instant occurredAt
    ) {}

    public record SuspiciousEventEntry(
            String eventId,
            String accountId,
            String signalType,
            String ipMasked,
            Instant occurredAt
    ) {}

    public record LoginHistoryResponse(List<LoginHistoryEntry> content) {}

    public record SuspiciousResponse(List<SuspiciousEventEntry> content) {}
}
