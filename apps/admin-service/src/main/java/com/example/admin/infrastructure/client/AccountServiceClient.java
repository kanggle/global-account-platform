package com.example.admin.infrastructure.client;

import com.example.admin.application.exception.DownstreamFailureException;
import com.example.admin.application.exception.NonRetryableDownstreamException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calls account-service internal endpoints to lock/unlock accounts.
 * Contract: specs/contracts/http/internal/admin-to-account.md
 */
@Slf4j
@Component
public class AccountServiceClient {

    private final RestClient restClient;
    private final String internalToken;

    public AccountServiceClient(
            @Value("${admin.account-service.base-url}") String baseUrl,
            @Value("${admin.downstream.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${admin.downstream.read-timeout-ms:10000}") int readTimeoutMs,
            @Value("${admin.downstream.internal-token:}") String internalToken) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
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

    @Retry(name = "accountService")
    @CircuitBreaker(name = "accountService")
    public LockResponse lock(String accountId,
                             String operatorId,
                             String reason,
                             String ticketId,
                             String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", "ADMIN_LOCK");
        body.put("operatorId", operatorId);
        if (ticketId != null) body.put("ticketId", ticketId);

        return callPost("/internal/accounts/" + accountId + "/lock",
                body, operatorId, idempotencyKey, LockResponse.class);
    }

    @Retry(name = "accountService")
    @CircuitBreaker(name = "accountService")
    public LockResponse unlock(String accountId,
                               String operatorId,
                               String reason,
                               String ticketId,
                               String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", "ADMIN_UNLOCK");
        body.put("operatorId", operatorId);
        if (ticketId != null) body.put("ticketId", ticketId);

        return callPost("/internal/accounts/" + accountId + "/unlock",
                body, operatorId, idempotencyKey, LockResponse.class);
    }

    private <T> T callPost(String path, Map<String, Object> body,
                           String operatorId, String idempotencyKey, Class<T> responseType) {
        try {
            return restClient.post()
                    .uri(path)
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
                    .body(responseType);
        } catch (RestClientResponseException e) {
            log.warn("account-service returned {} on {}: {}", e.getStatusCode(), path, e.getMessage());
            if (e.getStatusCode().is4xxClientError()) {
                throw new NonRetryableDownstreamException(
                        "account-service error " + e.getStatusCode().value(), e);
            }
            throw new DownstreamFailureException(
                    "account-service error " + e.getStatusCode().value(), e);
        } catch (Exception e) {
            log.error("account-service call failed on {}", path, e);
            throw new DownstreamFailureException("account-service unavailable", e);
        }
    }

    public record LockResponse(
            String accountId,
            String previousStatus,
            String currentStatus,
            Instant lockedAt,
            Instant unlockedAt
    ) {}
}
