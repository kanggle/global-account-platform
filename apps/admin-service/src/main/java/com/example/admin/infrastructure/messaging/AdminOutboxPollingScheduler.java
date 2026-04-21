package com.example.admin.infrastructure.messaging;

import com.example.messaging.outbox.OutboxPollingScheduler;
import com.example.messaging.outbox.OutboxPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("!standalone")
public class AdminOutboxPollingScheduler extends OutboxPollingScheduler {

    public AdminOutboxPollingScheduler(OutboxPublisher outboxPublisher,
                                       KafkaTemplate<String, String> kafkaTemplate,
                                       @Qualifier("outboxTaskScheduler") ThreadPoolTaskScheduler outboxTaskScheduler) {
        super(outboxPublisher, kafkaTemplate, outboxTaskScheduler);
    }

    @Override
    protected String resolveTopic(String eventType) {
        return switch (eventType) {
            case "admin.action.performed" -> "admin.action.performed";
            default -> throw new IllegalArgumentException("Unknown admin event type: " + eventType);
        };
    }
}
