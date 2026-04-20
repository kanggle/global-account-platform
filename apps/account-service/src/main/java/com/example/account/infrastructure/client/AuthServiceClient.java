package com.example.account.infrastructure.client;

import com.example.account.application.port.AuthServicePort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * HTTP client adapter for auth-service. Mirrors the resilience pattern used by
 * auth-service's AccountServiceClient: 3s connect / 5s read, 2 retries with
 * exponential-random backoff (no retry on 4xx), circuit breaker 50% / 10s.
 *
 * <p>On 409 the signup transaction should abort with a duplicate-account error;
 * everything else surfaces as {@link AuthServicePort.AuthServiceUnavailable} so
 * the @Transactional signup rolls back fail-closed.</p>
 */
@Slf4j
@Component
public class AuthServiceClient implements AuthServicePort {

    private static final String CREDENTIALS_PATH = "/internal/auth/credentials";

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public AuthServiceClient(
            @Value("${account.auth-service.base-url:http://localhost:8081}") String baseUrl,
            @Value("${account.auth-service.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${account.auth-service.read-timeout-ms:5000}") int readTimeoutMs) {

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();

        this.circuitBreaker = CircuitBreaker.of("authService", CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build());

        this.retry = Retry.of("authService", RetryConfig.custom()
                .maxAttempts(3) // 1 initial + 2 retries per auth-internal.md
                .intervalFunction(io.github.resilience4j.core.IntervalFunction
                        .ofExponentialRandomBackoff(Duration.ofMillis(500)))
                .ignoreExceptions(HttpClientErrorException.class)
                .retryExceptions(Exception.class)
                .build());
    }

    @Override
    public void createCredential(String accountId, String email, String password) {
        Runnable op = () -> doCreateCredential(accountId, email, password);
        Runnable retrying = Retry.decorateRunnable(retry, op);
        Runnable resilient = CircuitBreaker.decorateRunnable(circuitBreaker, retrying);

        try {
            resilient.run();
        } catch (HttpClientErrorException.Conflict e) {
            throw new CredentialAlreadyExistsConflict(accountId);
        } catch (HttpClientErrorException e) {
            // Any other 4xx is a contract violation — treat as unavailable so the signup
            // transaction rolls back rather than silently continuing with a bad payload.
            log.error("auth-service credential write returned 4xx {}: {}",
                    e.getStatusCode(), e.getMessage());
            throw new AuthServiceUnavailable("auth-service rejected credential create", e);
        } catch (Exception e) {
            log.error("auth-service credential write failed after retries: {}", e.getMessage());
            throw new AuthServiceUnavailable("auth-service is unavailable", e);
        }
    }

    private void doCreateCredential(String accountId, String email, String password) {
        Map<String, String> body = Map.of(
                "accountId", accountId,
                "email", email,
                "password", password
        );
        restClient.post()
                .uri(CREDENTIALS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                    throw HttpClientErrorException.create(
                            resp.getStatusCode(), "auth-service 4xx",
                            resp.getHeaders(), new byte[0], null);
                })
                .toBodilessEntity();
    }
}
