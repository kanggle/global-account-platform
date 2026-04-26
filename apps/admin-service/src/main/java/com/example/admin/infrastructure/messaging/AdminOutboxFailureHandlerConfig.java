package com.example.admin.infrastructure.messaging;

import com.example.messaging.outbox.OutboxFailureHandler;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers an {@link OutboxFailureHandler} that increments the
 * {@code admin_outbox_publish_failures} Micrometer counter (tagged with
 * {@code event_type}) on every Kafka publish failure observed by the shared
 * {@code OutboxPollingScheduler}.
 *
 * <p>Mirrors the {@code account-service} / {@code auth-service} pattern. The
 * counter restores the metric previously emitted by the per-service scheduler
 * subclass that was removed in TASK-BE-002 and re-introduced in TASK-BE-120.
 */
@Configuration
class AdminOutboxFailureHandlerConfig {

    @Bean
    OutboxFailureHandler outboxFailureHandler(MeterRegistry meterRegistry) {
        return (eventType, aggregateId, e) ->
                meterRegistry.counter("admin_outbox_publish_failures", "event_type", eventType).increment();
    }
}
