package com.example.security.consumer;

import com.example.security.application.RecordLoginHistoryUseCase;
import com.example.security.consumer.handler.EventDedupService;
import com.example.security.domain.history.LoginHistoryEntry;
import com.example.security.domain.history.LoginOutcome;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * Base class for auth event consumers. Handles dedup, mapping, and delegation to use-case.
 *
 * Trace propagation is handled by {@code EventContextRecordInterceptor} from
 * libs/java-observability, which is auto-configured via
 * {@code KafkaEventContextAutoConfiguration}. No manual MDC manipulation needed here.
 */
@Slf4j
public abstract class AbstractAuthEventConsumer {

    protected final ObjectMapper objectMapper;
    protected final EventDedupService dedupService;
    protected final RecordLoginHistoryUseCase recordLoginHistoryUseCase;

    protected AbstractAuthEventConsumer(ObjectMapper objectMapper,
                                         EventDedupService dedupService,
                                         RecordLoginHistoryUseCase recordLoginHistoryUseCase) {
        this.objectMapper = objectMapper;
        this.dedupService = dedupService;
        this.recordLoginHistoryUseCase = recordLoginHistoryUseCase;
    }

    protected void processEvent(ConsumerRecord<String, String> record, LoginOutcome defaultOutcome) {
        try {
            JsonNode envelope = objectMapper.readTree(record.value());
            String eventId = envelope.path("eventId").asText();
            String eventType = envelope.path("eventType").asText();

            if (eventId.isBlank()) {
                log.warn("Event missing eventId, skipping. topic={}", record.topic());
                return;
            }

            // Fast-path: Redis dedup check (optimization only, not authoritative)
            if (dedupService.isDuplicate(eventId)) {
                log.info("Duplicate event skipped (Redis fast-path): eventId={}, topic={}", eventId, record.topic());
                return;
            }

            LoginOutcome outcome = resolveOutcome(envelope, defaultOutcome);
            LoginHistoryEntry entry = AuthEventMapper.toLoginHistoryEntry(envelope, outcome);

            // Atomic: save + mark processed in single @Transactional method.
            // MySQL UNIQUE constraint on processed_events is the ultimate guard.
            boolean processed = recordLoginHistoryUseCase.execute(entry, eventType);

            if (processed) {
                // Update Redis cache after successful DB commit
                dedupService.markProcessedInRedis(eventId);
                log.info("Processed event: eventId={}, topic={}, outcome={}", eventId, record.topic(), outcome);
            } else {
                log.info("Duplicate event skipped (DB constraint): eventId={}, topic={}", eventId, record.topic());
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize event from topic={}", record.topic(), e);
            throw new RuntimeException("Deserialization failed", e);
        }
    }

    protected LoginOutcome resolveOutcome(JsonNode envelope, LoginOutcome defaultOutcome) {
        return defaultOutcome;
    }
}
