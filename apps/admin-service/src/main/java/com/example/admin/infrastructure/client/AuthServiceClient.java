package com.example.admin.infrastructure.client;

import com.example.admin.application.exception.DownstreamFailureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calls auth-service internal endpoints to force-logout (revoke all sessions).
 * Contract: specs/contracts/http/internal/admin-to-auth.md
 */
@Slf4j
@Component
public class AuthServiceClient {

    private final RestClient restClient;
    private final String internalToken;

    public AuthServiceClient(
            @Value("${admin.auth-service.base-url}") String baseUrl,
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

    public ForceLogoutResponse forceLogout(String accountId,
                                           String operatorId,
                                           String reason,
                                           String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        body.put("operatorId", operatorId);

        try {
            return restClient.post()
                    .uri("/internal/auth/accounts/{accountId}/force-logout", accountId)
                    .headers(h -> {
                        h.add("Idempotency-Key", idempotencyKey);
                        h.add("X-Operator-ID", operatorId);
                        if (internalToken != null && !internalToken.isBlank()) {
                            h.add("X-Internal-Token", internalToken);
                        }
                        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                    })
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> {
                        throw HttpClientErrorException.create(
                                resp.getStatusCode(), resp.getStatusText(),
                                resp.getHeaders(), resp.getBody().readAllBytes(), null);
                    })
                    .body(ForceLogoutResponse.class);
        } catch (HttpClientErrorException e) {
            log.warn("auth-service force-logout returned {}: {}", e.getStatusCode(), e.getMessage());
            throw new DownstreamFailureException("auth-service error " + e.getStatusCode().value(), e);
        } catch (Exception e) {
            log.error("auth-service force-logout failed", e);
            throw new DownstreamFailureException("auth-service unavailable", e);
        }
    }

    public record ForceLogoutResponse(
            String accountId,
            Integer revokedTokenCount,
            Instant revokedAt
    ) {}
}
