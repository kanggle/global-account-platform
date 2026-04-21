package com.example.messaging.outbox;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base class for Outbox polling scheduler.
 *
 * Subclasses must implement {@link #resolveTopic(String)} to map event types
 * to Kafka topic names specific to each service.
 *
 * Subclasses may override {@link #onKafkaSendFailure(String, String, Exception)}
 * to add service-specific failure handling (e.g., metrics recording).
 *
 * <p>Lifecycle note (TASK-BE-073): the polling loop guards against running
 * during Spring context shutdown. When the bean receives {@code @PreDestroy},
 * the {@code running} flag flips to {@code false}, causing any subsequent
 * scheduler tick to return immediately. This prevents
 * {@code CannotCreateTransactionException} storms when HikariCP has already
 * begun closing but a {@code @Scheduled} task was still in flight (observed
 * in Testcontainers-based integration tests that spin up and tear down
 * multiple Spring test contexts in the same JVM).</p>
 */
@Slf4j
@RequiredArgsConstructor
public abstract class OutboxPollingScheduler {

    private final OutboxPublisher outboxPublisher;
    private final KafkaTemplate<String, String> kafkaTemplate;

    private final AtomicBoolean running = new AtomicBoolean(true);

    @Scheduled(fixedDelayString = "${outbox.polling.interval-ms:1000}")
    public void pollAndPublish() {
        if (!running.get()) {
            // Context is shutting down; skip this tick so we don't touch a
            // closing JDBC pool / transaction manager.
            return;
        }
        outboxPublisher.publishPendingEvents(this::sendToKafka);
    }

    /**
     * Stop accepting new poll ticks. Invoked by Spring when the bean is
     * destroyed (typically during context shutdown). Idempotent.
     */
    @PreDestroy
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("OutboxPollingScheduler stop requested; subsequent ticks will be skipped.");
        }
    }

    private boolean sendToKafka(String eventType, String aggregateId, String payload) {
        try {
            String topic = resolveTopic(eventType);
            kafkaTemplate.send(topic, aggregateId, payload).get();
            return true;
        } catch (Exception e) {
            log.error("Kafka send failed: eventType={}, aggregateId={}", eventType, aggregateId, e);
            onKafkaSendFailure(eventType, aggregateId, e);
            return false;
        }
    }

    /**
     * Maps an event type to the corresponding Kafka topic name.
     *
     * @param eventType the domain event type name (e.g. "OrderPlaced")
     * @return the Kafka topic name
     * @throws IllegalArgumentException if the event type is unknown
     */
    protected abstract String resolveTopic(String eventType);

    /**
     * Hook called when Kafka send fails. Subclasses can override to record metrics
     * or perform other failure handling.
     *
     * @param eventType   the event type that failed to send
     * @param aggregateId the aggregate ID
     * @param e           the exception that caused the failure
     */
    protected void onKafkaSendFailure(String eventType, String aggregateId, Exception e) {
        // default: no additional handling
    }
}
