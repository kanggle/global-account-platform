package com.example.admin.infrastructure.messaging;

import com.example.messaging.outbox.OutboxPollingScheduler;
import com.example.messaging.outbox.OutboxPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("!standalone")
public class AdminOutboxPollingScheduler extends OutboxPollingScheduler {

    public AdminOutboxPollingScheduler(OutboxPublisher outboxPublisher,
                                       KafkaTemplate<String, String> kafkaTemplate) {
        super(outboxPublisher, kafkaTemplate);
    }

    @Override
    protected String resolveTopic(String eventType) {
        return switch (eventType) {
            case "admin.action.performed" -> "admin.action.performed";
            default -> throw new IllegalArgumentException("Unknown admin event type: " + eventType);
        };
    }
}
