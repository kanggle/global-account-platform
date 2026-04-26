package com.example.auth.infrastructure.messaging;

import com.example.messaging.outbox.OutboxFailureHandler;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AuthOutboxFailureHandlerConfig {

    @Bean
    OutboxFailureHandler outboxFailureHandler(MeterRegistry meterRegistry) {
        return (eventType, aggregateId, e) ->
                meterRegistry.counter("auth_outbox_publish_failures", "event_type", eventType).increment();
    }
}
