package com.example.admin.infrastructure.client;

import com.example.admin.application.exception.DownstreamFailureException;
import com.example.admin.application.exception.NonRetryableDownstreamException;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the TASK-BE-033 fail-fast contract on the audit read path:
 *
 *   - {@link SecurityServiceClient} propagates 5xx as {@link DownstreamFailureException}
 *     instead of silently returning an empty list.
 *   - Repeated downstream failures trip the {@link CircuitBreaker} to OPEN;
 *     subsequent calls fail immediately with {@link CallNotPermittedException}
 *     (which {@code AdminExceptionHandler} maps to 503 CIRCUIT_OPEN).
 *   - HALF_OPEN → CLOSED recovery after a successful probe response.
 */
class SecurityServiceClientCircuitBreakerTest {

    private static WireMockServer wireMock;
    private static SecurityServiceClient client;
    private static CircuitBreaker circuitBreaker;
    private static Retry retry;

    @BeforeAll
    static void startAll() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();

        client = new SecurityServiceClient(
                "http://localhost:" + wireMock.port(),
                500,
                1000,
                "test-token");

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .waitDurationInOpenState(Duration.ofMillis(200))
                .permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .ignoreExceptions(NonRetryableDownstreamException.class)
                .build();
        circuitBreaker = CircuitBreaker.of("securityService", cbConfig);

        // Match runtime: retry skips CB-open rejections so they surface cleanly.
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(1)
                .ignoreExceptions(NonRetryableDownstreamException.class, CallNotPermittedException.class)
                .build();
        retry = Retry.of("securityService", retryConfig);
    }

    @AfterAll
    static void stopAll() {
        wireMock.stop();
    }

    @BeforeEach
    void reset() {
        wireMock.resetAll();
        circuitBreaker.reset();
    }

    private java.util.List<SecurityServiceClient.LoginHistoryEntry> callLoginHistory() {
        Supplier<java.util.List<SecurityServiceClient.LoginHistoryEntry>> base =
                () -> client.queryLoginHistory("acc-x", Instant.now().minusSeconds(60), Instant.now());
        Supplier<java.util.List<SecurityServiceClient.LoginHistoryEntry>> cbWrapped =
                CircuitBreaker.decorateSupplier(circuitBreaker, base);
        return Retry.decorateSupplier(retry, cbWrapped).get();
    }

    @Test
    void propagates_downstream_failure_on_5xx_not_empty_list() {
        wireMock.stubFor(get(urlPathMatching("/internal/security/login-history.*"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(this::callLoginHistory)
                .isInstanceOf(DownstreamFailureException.class);
    }

    @Test
    void trips_to_open_after_threshold_and_rejects_subsequent_calls() {
        wireMock.stubFor(get(urlPathMatching("/internal/security/login-history.*"))
                .willReturn(aResponse().withStatus(503)));

        // Drive 4 failing calls to hit minimumNumberOfCalls with a 100% failure rate.
        for (int i = 0; i < 4; i++) {
            try {
                callLoginHistory();
            } catch (RuntimeException ignored) {
                // expected
            }
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // With the circuit OPEN the next call must be rejected immediately,
        // without reaching WireMock.
        int callsBefore = wireMock.countRequestsMatching(
                com.github.tomakehurst.wiremock.client.WireMock
                        .getRequestedFor(urlPathMatching("/internal/security/login-history.*"))
                        .build()).getCount();

        assertThatThrownBy(this::callLoginHistory)
                .isInstanceOf(CallNotPermittedException.class);

        int callsAfter = wireMock.countRequestsMatching(
                com.github.tomakehurst.wiremock.client.WireMock
                        .getRequestedFor(urlPathMatching("/internal/security/login-history.*"))
                        .build()).getCount();
        assertThat(callsAfter).isEqualTo(callsBefore);
    }

    @Test
    void recovers_to_closed_after_half_open_success() throws InterruptedException {
        // Force OPEN state deterministically.
        circuitBreaker.transitionToOpenState();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Wait past waitDurationInOpenState so the CB auto-transitions to HALF_OPEN.
        Thread.sleep(300);

        wireMock.stubFor(get(urlPathMatching("/internal/security/login-history.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"content\":[]}")));

        // First probe triggers HALF_OPEN and, on success, closes the circuit.
        callLoginHistory();

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
