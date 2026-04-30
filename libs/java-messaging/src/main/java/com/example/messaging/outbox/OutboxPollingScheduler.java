package com.example.messaging.outbox;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Outbox polling scheduler.
 *
 * <p>Reads {@code outbox.topic-mapping} from configuration and publishes pending
 * outbox rows to Kafka. Optional {@link OutboxFailureHandler} can be provided
 * (e.g. to increment Micrometer counters) without adding Micrometer as a
 * compile-time dependency to this library.
 *
 * <h2>Lifecycle (TASK-BE-077, TASK-BE-243, TASK-BE-245)</h2>
 *
 * <p>Uses a dedicated {@link ThreadPoolTaskScheduler} ({@code outboxTaskScheduler})
 * whose lifetime is bound to the owning {@code ApplicationContext}. This prevents
 * the orphaned-thread / closing-pool issue described in PR #44 / TASK-BE-076.
 *
 * <p>{@link #start()} is triggered by {@link org.springframework.boot.context.event.ApplicationReadyEvent}
 * (not {@code @PostConstruct}) to avoid a {@code BeanCurrentlyInCreationException}
 * race when the background polling thread accesses {@code transactionManager} while
 * the main thread still holds the singleton lock during cold-start (TASK-BE-243).
 *
 * <p>{@link #start()} is idempotent: if invoked more than once (e.g. by parent/child
 * context hierarchies that each publish {@code ApplicationReadyEvent}) only the first
 * call schedules polling; subsequent calls are silently ignored. {@link #stop()} does
 * NOT reset the idempotency guard — once stopped, the scheduler cannot be restarted
 * within the same context lifecycle (TASK-BE-245).
 */
@Slf4j
public class OutboxPollingScheduler {

    private final OutboxPublisher outboxPublisher;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ThreadPoolTaskScheduler taskScheduler;
    private final Map<String, String> topicMapping;
    @Nullable
    private final OutboxFailureHandler failureHandler;

    @Value("${outbox.polling.interval-ms:1000}")
    private long intervalMs;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private ScheduledFuture<?> scheduledFuture;

    public OutboxPollingScheduler(OutboxPublisher outboxPublisher,
                                  KafkaTemplate<String, String> kafkaTemplate,
                                  ThreadPoolTaskScheduler outboxTaskScheduler,
                                  OutboxProperties outboxProperties,
                                  @Nullable OutboxFailureHandler failureHandler) {
        this.outboxPublisher = outboxPublisher;
        this.kafkaTemplate = kafkaTemplate;
        this.taskScheduler = outboxTaskScheduler;
        this.topicMapping = outboxProperties.getTopicMapping();
        this.failureHandler = failureHandler;
    }

    /**
     * Starts outbox polling after the application context is fully initialised.
     *
     * <p>Triggering from {@link ApplicationReadyEvent} (TASK-BE-243) instead of
     * {@code @PostConstruct} prevents the race condition where the background
     * {@code outbox-1} thread attempts to access {@code transactionManager} while
     * the main thread still holds the {@code DataSourceAutoConfiguration} singleton
     * lock, causing a {@code BeanCurrentlyInCreationException}.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!started.compareAndSet(false, true)) {
            log.debug("OutboxPollingScheduler.start() called but already started; ignoring");
            return;
        }
        scheduledFuture = taskScheduler.scheduleWithFixedDelay(
                this::pollAndPublish, Duration.ofMillis(intervalMs));
        log.info("OutboxPollingScheduler started: intervalMs={}", intervalMs);
    }

    public void pollAndPublish() {
        if (!running.get()) {
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
            if (failureHandler != null) {
                failureHandler.onFailure(eventType, aggregateId, e);
            }
            return false;
        }
    }

    private String resolveTopic(String eventType) {
        String topic = topicMapping.get(eventType);
        if (topic == null) {
            throw new IllegalStateException("No topic mapping for event type: " + eventType);
        }
        return topic;
    }
}
