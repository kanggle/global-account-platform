package com.example.membership.infrastructure.kafka;

import com.example.messaging.outbox.OutboxPollingScheduler;
import com.example.messaging.outbox.OutboxPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("!standalone")
@ConditionalOnProperty(name = "outbox.polling.enabled", havingValue = "true", matchIfMissing = true)
public class MembershipOutboxPollingScheduler extends OutboxPollingScheduler {

    public MembershipOutboxPollingScheduler(OutboxPublisher outboxPublisher,
                                            KafkaTemplate<String, String> kafkaTemplate,
                                            @Qualifier("outboxTaskScheduler") ThreadPoolTaskScheduler outboxTaskScheduler) {
        super(outboxPublisher, kafkaTemplate, outboxTaskScheduler);
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
