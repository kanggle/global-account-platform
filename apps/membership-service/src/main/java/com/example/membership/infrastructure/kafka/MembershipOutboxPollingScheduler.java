package com.example.membership.infrastructure.kafka;

import com.example.messaging.outbox.OutboxPollingScheduler;
import com.example.messaging.outbox.OutboxPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("!standalone")
public class MembershipOutboxPollingScheduler extends OutboxPollingScheduler {

    public MembershipOutboxPollingScheduler(OutboxPublisher outboxPublisher,
                                            KafkaTemplate<String, String> kafkaTemplate) {
        super(outboxPublisher, kafkaTemplate);
    }

    @Override
    protected String resolveTopic(String eventType) {
        return switch (eventType) {
            case "membership.subscription.activated" -> "membership.subscription.activated";
            case "membership.subscription.expired"   -> "membership.subscription.expired";
            case "membership.subscription.cancelled" -> "membership.subscription.cancelled";
            default -> throw new IllegalArgumentException("Unknown membership event type: " + eventType);
        };
    }

    @Override
    protected void onKafkaSendFailure(String eventType, String aggregateId, Exception e) {
        log.error("Membership outbox relay failed: eventType={}, aggregateId={}", eventType, aggregateId, e);
    }
}
