package com.example.auth.infrastructure.client;

import com.example.auth.application.exception.AccountServiceUnavailableException;
import com.example.auth.application.port.AccountServicePort;
import com.example.auth.application.result.CredentialLookupResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Internal HTTP client for account-service.
 * Configured with timeouts (connect=3s, read=5s), retry (2 retries, exponential backoff + jitter,
 * no retry on 4xx), and circuit breaker (50% failure rate / 10s sliding window).
 */
@Slf4j
@Component
public class AccountServiceClient implements AccountServicePort {

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public AccountServiceClient(
            @Value("${auth.account-service.base-url}") String baseUrl,
            @Value("${auth.account-service.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${auth.account-service.read-timeout-ms:5000}") int readTimeoutMs) {

        // Configure HTTP client with timeouts
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();

        // Circuit breaker: 50% failure rate, 10s sliding window
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();
        this.circuitBreaker = CircuitBreaker.of("accountService", cbConfig);

        // Retry: 2 retries, exponential backoff + jitter, no retry on 4xx
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3) // 1 initial + 2 retries
                .intervalFunction(io.github.resilience4j.core.IntervalFunction
                        .ofExponentialRandomBackoff(Duration.ofMillis(500)))
                .ignoreExceptions(HttpClientErrorException.class)
                .retryExceptions(Exception.class)
                .build();
        this.retry = Retry.of("accountService", retryConfig);
    }

    @Override
    public Optional<CredentialLookupResult> lookupCredentialsByEmail(String email) {
        Supplier<Optional<CredentialLookupResult>> supplier = () -> doLookup(email);

        // Wrap with retry, then circuit breaker (retry is inner, CB is outer)
        Supplier<Optional<CredentialLookupResult>> retryingSupplier =
                Retry.decorateSupplier(retry, supplier);
        Supplier<Optional<CredentialLookupResult>> resilientSupplier =
                CircuitBreaker.decorateSupplier(circuitBreaker, retryingSupplier);

        try {
            return resilientSupplier.get();
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (HttpClientErrorException e) {
            // Other 4xx errors: not retried, propagate as empty or error
            log.warn("Account service returned client error {}: {}", e.getStatusCode(), e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.error("Account service call failed after retries: {}", e.getMessage());
            throw new AccountServiceUnavailableException("Account service is unavailable", e);
        }
    }

    private Optional<CredentialLookupResult> doLookup(String email) {
        try {
            CredentialLookupResult result = restClient.get()
                    .uri("/internal/accounts/credentials?email={email}", email)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        if (response.getStatusCode().value() == 404) {
                            throw HttpClientErrorException.create(
                                    response.getStatusCode(), "Not Found",
                                    response.getHeaders(), new byte[0], null);
                        }
                        throw HttpClientErrorException.create(
                                response.getStatusCode(), "Client Error",
                                response.getHeaders(), new byte[0], null);
                    })
                    .body(CredentialLookupResult.class);

            return Optional.ofNullable(result);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (HttpClientErrorException e) {
            throw e; // Let retry logic decide (4xx won't be retried)
        } catch (Exception e) {
            throw new RuntimeException("Account service communication error", e);
        }
    }
}
