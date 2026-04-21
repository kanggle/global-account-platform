package com.example.auth.infrastructure.messaging;

import com.example.messaging.outbox.OutboxPublisher;
import com.example.messaging.outbox.OutboxPollingScheduler;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Auth-service outbox relay: polls unpublished outbox rows and publishes to Kafka.
 *
 * Maps auth event types to their corresponding Kafka topics.
 * Partition key = aggregate_id (account_id) for per-account ordering.
 */
@Slf4j
@Component
@Profile("!standalone")
public class AuthOutboxPollingScheduler extends OutboxPollingScheduler {

    private final MeterRegistry meterRegistry;

    public AuthOutboxPollingScheduler(OutboxPublisher outboxPublisher,
                                      KafkaTemplate<String, String> kafkaTemplate,
                                      @Qualifier("outboxTaskScheduler") ThreadPoolTaskScheduler outboxTaskScheduler,
                                      MeterRegistry meterRegistry) {
        super(outboxPublisher, kafkaTemplate, outboxTaskScheduler);
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected String resolveTopic(String eventType) {
        return switch (eventType) {
            case "auth.login.attempted"       -> "auth.login.attempted";
            case "auth.login.failed"          -> "auth.login.failed";
            case "auth.login.succeeded"       -> "auth.login.succeeded";
            case "auth.token.refreshed"       -> "auth.token.refreshed";
            case "auth.token.reuse.detected"  -> "auth.token.reuse.detected";
            case "auth.session.created"       -> "auth.session.created";
            case "auth.session.revoked"       -> "auth.session.revoked";
            default -> throw new IllegalArgumentException("Unknown auth event type: " + eventType);
        };
    }

    @Override
    protected void onKafkaSendFailure(String eventType, String aggregateId, Exception e) {
        meterRegistry.counter("auth_outbox_publish_failures",
                "event_type", eventType).increment();
        log.error("Auth outbox relay failed: eventType={}, aggregateId={}", eventType, aggregateId, e);
    }
}
