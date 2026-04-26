package com.example.account.infrastructure.messaging;

import com.example.messaging.outbox.OutboxFailureHandler;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AccountOutboxFailureHandlerConfig {

    @Bean
    OutboxFailureHandler outboxFailureHandler(MeterRegistry meterRegistry) {
        return (eventType, aggregateId, e) ->
                meterRegistry.counter("account_outbox_publish_failures", "event_type", eventType).increment();
    }
}
