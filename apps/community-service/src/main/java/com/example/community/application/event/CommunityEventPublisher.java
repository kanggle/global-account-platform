package com.example.community.application.event;

import com.example.messaging.outbox.OutboxWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class CommunityEventPublisher {

    private static final String AGGREGATE_TYPE = "community";
    private static final String SOURCE = "community-service";

    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;

    public void publishPostPublished(String postId, String authorAccountId, String type,
                                     String visibility, Instant publishedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("postId", postId);
        payload.put("authorAccountId", authorAccountId);
        payload.put("type", type);
        payload.put("visibility", visibility);
        payload.put("publishedAt", publishedAt.toString());
        writeEvent("community.post.published", postId, payload);
    }

    public void publishCommentCreated(String commentId, String postId,
                                      String postAuthorAccountId, String commenterAccountId,
                                      Instant createdAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commentId", commentId);
        payload.put("postId", postId);
        payload.put("postAuthorAccountId", postAuthorAccountId);
        payload.put("commenterAccountId", commenterAccountId);
        payload.put("createdAt", createdAt.toString());
        writeEvent("community.comment.created", postId, payload);
    }

    public void publishReactionAdded(String postId, String reactorAccountId, String emojiCode,
                                     boolean isNew, Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("postId", postId);
        payload.put("reactorAccountId", reactorAccountId);
        payload.put("emojiCode", emojiCode);
        payload.put("isNew", isNew);
        payload.put("occurredAt", occurredAt.toString());
        writeEvent("community.reaction.added", postId, payload);
    }

    private void writeEvent(String eventType, String aggregateId, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", eventType);
        envelope.put("source", SOURCE);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("schemaVersion", 1);
        envelope.put("partitionKey", aggregateId);
        envelope.put("payload", payload);

        try {
            String json = objectMapper.writeValueAsString(envelope);
            outboxWriter.save(AGGREGATE_TYPE, aggregateId, eventType, json);
        } catch (JsonProcessingException e) {
            // Rethrow to trigger transaction rollback — outbox write failed while
            // state changes would otherwise persist (inconsistency).
            log.error("Failed to serialize event payload for {}: {}", eventType, e.getMessage());
            throw new IllegalStateException("Failed to serialize outbox event: " + eventType, e);
        }
    }
}
