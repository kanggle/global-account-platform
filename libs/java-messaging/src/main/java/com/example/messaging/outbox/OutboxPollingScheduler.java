package com.example.messaging.outbox;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base class for Outbox polling scheduler.
 *
 * <p>Subclasses must implement {@link #resolveTopic(String)} to map event
 * types to Kafka topic names specific to each service. Subclasses may
 * override {@link #onKafkaSendFailure(String, String, Exception)} to add
 * service-specific failure handling (e.g., metrics recording).
 *
 * <h2>Lifecycle (TASK-BE-077)</h2>
 *
 * <p>Previously this class used Spring's {@code @Scheduled} annotation,
 * which is backed by a singleton {@code TaskScheduler} whose thread pool
 * outlives individual {@code ApplicationContext} instances. Testcontainers
 * integration tests that rotate Spring contexts therefore saw the
 * {@code scheduling-1} thread keep polling with a closure that captured
 * the destroyed context's HikariCP pool — producing
 * {@code HikariPool-N ... total=0} + {@code CommunicationsException} on
 * every subsequent tick (diagnosed from PR #44 / TASK-BE-076 CI artifacts).
 *
 * <p>The scheduler now receives a dedicated {@link ThreadPoolTaskScheduler}
 * ({@code outboxTaskScheduler}) bean whose lifetime is bound to the owning
 * {@code ApplicationContext}. {@link #start()} registers a fixed-delay task
 * on that executor; {@link #stop()} cancels the {@link ScheduledFuture}
 * before the executor itself is shut down. Because the executor is a bean
 * with {@code destroyMethod = "shutdown"}, context close terminates every
 * outbox thread — no orphaned thread can reference a destroyed pool on the
 * next tick.
 *
 * <p>The {@link AtomicBoolean} running guard is retained as a belt-and-
 * suspenders defence: if a tick is already executing when {@link #stop()}
 * is called, it returns early on the next iteration instead of opening a
 * new JDBC transaction on a closing pool.
 */
@Slf4j
public abstract class OutboxPollingScheduler {

    private final OutboxPublisher outboxPublisher;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ThreadPoolTaskScheduler taskScheduler;

    @Value("${outbox.polling.interval-ms:1000}")
    private long intervalMs;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private ScheduledFuture<?> scheduledFuture;

    protected OutboxPollingScheduler(OutboxPublisher outboxPublisher,
                                     KafkaTemplate<String, String> kafkaTemplate,
                                     ThreadPoolTaskScheduler outboxTaskScheduler) {
        this.outboxPublisher = outboxPublisher;
        this.kafkaTemplate = kafkaTemplate;
        this.taskScheduler = outboxTaskScheduler;
    }

    /**
     * Register the polling task on the context-scoped executor. Invoked by
     * Spring once the bean and its dependencies are fully wired.
     */
    @PostConstruct
    public void start() {
        scheduledFuture = taskScheduler.scheduleWithFixedDelay(
                this::pollAndPublish, Duration.ofMillis(intervalMs));
        log.info("OutboxPollingScheduler started: intervalMs={}", intervalMs);
    }

    /**
     * Poll the outbox for pending events and publish them. Exposed so that
     * unit tests and integration tests can invoke the loop synchronously.
     * Normally driven by the {@code outboxTaskScheduler} executor registered
     * in {@link #start()}.
     */
    public void pollAndPublish() {
        if (!running.get()) {
            // Context is shutting down; skip this tick so we don't touch a
            // closing JDBC pool / transaction manager.
            return;
        }
        try {
            outboxPublisher.publishPendingEvents(this::sendToKafka);
        } catch (Exception e) {
            if (!running.get()) {
                log.debug("OutboxPollingScheduler tick failed during shutdown; suppressing.", e);
            } else {
                log.error("Unexpected error during outbox poll tick.", e);
            }
        }
    }

    /**
     * Stop accepting new poll ticks and cancel the scheduled task. Invoked
     * by Spring when the bean is destroyed (typically during context
     * shutdown). Idempotent.
     */
    @PreDestroy
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("OutboxPollingScheduler stop requested; cancelling scheduled task.");
            if (scheduledFuture != null) {
                scheduledFuture.cancel(false);
            }
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
