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
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;

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
        propagateTrace(record);

        try {
            JsonNode envelope = objectMapper.readTree(record.value());
            String eventId = envelope.path("eventId").asText();
            String eventType = envelope.path("eventType").asText();

            if (eventId == null || eventId.isBlank()) {
                log.warn("Event missing eventId, skipping. topic={}", record.topic());
                return;
            }

            if (dedupService.isDuplicate(eventId)) {
                log.info("Duplicate event skipped: eventId={}, topic={}", eventId, record.topic());
                return;
            }

            LoginOutcome outcome = resolveOutcome(envelope, defaultOutcome);
            LoginHistoryEntry entry = AuthEventMapper.toLoginHistoryEntry(envelope, outcome);

            recordLoginHistoryUseCase.execute(entry);
            dedupService.markProcessed(eventId, eventType);

            log.info("Processed event: eventId={}, topic={}, outcome={}", eventId, record.topic(), outcome);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize event from topic={}", record.topic(), e);
            throw new RuntimeException("Deserialization failed", e);
        } finally {
            MDC.remove("traceId");
        }
    }

    protected LoginOutcome resolveOutcome(JsonNode envelope, LoginOutcome defaultOutcome) {
        return defaultOutcome;
    }

    private void propagateTrace(ConsumerRecord<String, String> record) {
        Header traceparent = record.headers().lastHeader("traceparent");
        if (traceparent != null) {
            String traceValue = new String(traceparent.value(), StandardCharsets.UTF_8);
            // traceparent format: version-traceId-spanId-flags
            String[] parts = traceValue.split("-");
            if (parts.length >= 2) {
                MDC.put("traceId", parts[1]);
            }
        }
    }
}
