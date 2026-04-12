package com.example.security.consumer.handler;

import com.example.messaging.outbox.ProcessedEventJpaRepository;
import com.example.messaging.outbox.ProcessedEventJpaEntity;
import com.example.security.infrastructure.redis.RedisEventDedupStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventDedupService {

    private final RedisEventDedupStore redisStore;
    private final ProcessedEventJpaRepository processedEventRepository;

    /**
     * Check if this event has already been processed.
     * Fast path: Redis check.
     * Fallback: MySQL processed_events table.
     */
    public boolean isDuplicate(String eventId) {
        // Fast path: Redis
        if (redisStore.isDuplicate(eventId)) {
            log.debug("Dedup hit (Redis) for eventId={}", eventId);
            return true;
        }

        // Fallback: MySQL
        if (processedEventRepository.existsByEventId(eventId)) {
            log.debug("Dedup hit (MySQL) for eventId={}", eventId);
            // Restore Redis cache for future checks
            redisStore.markProcessed(eventId);
            return true;
        }

        return false;
    }

    /**
     * Mark event as processed in both Redis and MySQL.
     */
    public void markProcessed(String eventId, String eventType) {
        redisStore.markProcessed(eventId);
        processedEventRepository.save(ProcessedEventJpaEntity.create(eventId, eventType));
        log.debug("Marked event as processed: eventId={}, eventType={}", eventId, eventType);
    }
}
