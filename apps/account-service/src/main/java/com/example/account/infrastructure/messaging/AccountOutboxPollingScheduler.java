package com.example.account.infrastructure.messaging;

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
 * Account-service outbox relay: polls unpublished outbox rows and publishes to Kafka.
 *
 * Maps account event types to their corresponding Kafka topics.
 * Partition key = aggregate_id (account_id) for per-account ordering.
 */
@Slf4j
@Component
@Profile("!standalone")
public class AccountOutboxPollingScheduler extends OutboxPollingScheduler {

    private final MeterRegistry meterRegistry;

    public AccountOutboxPollingScheduler(OutboxPublisher outboxPublisher,
                                         KafkaTemplate<String, String> kafkaTemplate,
                                         @Qualifier("outboxTaskScheduler") ThreadPoolTaskScheduler outboxTaskScheduler,
                                         MeterRegistry meterRegistry) {
        super(outboxPublisher, kafkaTemplate, outboxTaskScheduler);
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected String resolveTopic(String eventType) {
        return switch (eventType) {
            case "account.created"        -> "account.created";
            case "account.status.changed" -> "account.status.changed";
            case "account.locked"         -> "account.locked";
            case "account.unlocked"       -> "account.unlocked";
            case "account.deleted"        -> "account.deleted";
            default -> throw new IllegalArgumentException("Unknown account event type: " + eventType);
        };
    }

    @Override
    protected void onKafkaSendFailure(String eventType, String aggregateId, Exception e) {
        meterRegistry.counter("account_outbox_publish_failures",
                "event_type", eventType).increment();
        log.error("Account outbox relay failed: eventType={}, aggregateId={}", eventType, aggregateId, e);
    }
}
