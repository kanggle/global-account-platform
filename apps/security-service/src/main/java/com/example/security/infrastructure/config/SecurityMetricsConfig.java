package com.example.security.infrastructure.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Configuration
public class SecurityMetricsConfig {

    private final MeterRegistry meterRegistry;
    private final KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;
    private final AdminClient adminClient;
    private final AtomicLong dlqDepthCache = new AtomicLong(0);

    private static final List<String> DLQ_TOPICS = List.of(
            "auth.login.attempted.dlq",
            "auth.login.failed.dlq",
            "auth.login.succeeded.dlq",
            "auth.token.refreshed.dlq",
            "auth.token.reuse.detected.dlq"
    );

    public SecurityMetricsConfig(MeterRegistry meterRegistry,
                                  KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry,
                                  KafkaProperties kafkaProperties) {
        this.meterRegistry = meterRegistry;
        this.kafkaListenerEndpointRegistry = kafkaListenerEndpointRegistry;
        this.adminClient = AdminClient.create(kafkaProperties.buildAdminProperties(null));

        Gauge.builder("security_consumer_lag", this, SecurityMetricsConfig::computeConsumerLag)
                .description("Total consumer lag across all partitions")
                .register(meterRegistry);

        Gauge.builder("security_dlq_depth", this, config -> (double) config.dlqDepthCache.get())
                .description("Total DLQ depth across all DLQ topics")
                .register(meterRegistry);
    }

    @PreDestroy
    void closeAdminClient() {
        try {
            adminClient.close(java.time.Duration.ofSeconds(5));
        } catch (Exception e) {
            log.debug("Error closing AdminClient", e);
        }
    }

    private double computeConsumerLag() {
        try {
            long totalLag = 0;
            Collection<MessageListenerContainer> containers = kafkaListenerEndpointRegistry.getListenerContainers();
            for (MessageListenerContainer container : containers) {
                if (container.metrics() != null) {
                    Map<String, Map<org.apache.kafka.common.MetricName, ? extends org.apache.kafka.common.Metric>> metrics = container.metrics();
                    for (Map<org.apache.kafka.common.MetricName, ? extends org.apache.kafka.common.Metric> metricMap : metrics.values()) {
                        for (Map.Entry<org.apache.kafka.common.MetricName, ? extends org.apache.kafka.common.Metric> entry : metricMap.entrySet()) {
                            if ("records-lag-max".equals(entry.getKey().name())) {
                                Object value = entry.getValue().metricValue();
                                if (value instanceof Double d && !d.isNaN() && d > 0) {
                                    totalLag += d.longValue();
                                }
                            }
                        }
                    }
                }
            }
            return totalLag;
        } catch (Exception e) {
            log.debug("Failed to compute consumer lag metric", e);
            return 0;
        }
    }

    /**
     * Periodically computes DLQ depth by querying Kafka topic offsets via AdminClient.
     * Updates the cached value so that Prometheus scrapes read from the cache
     * instead of making blocking Kafka calls on the scrape thread.
     */
    @Scheduled(fixedDelay = 30_000)
    void refreshDlqDepth() {
        dlqDepthCache.set(computeDlqDepth());
    }

    /**
     * Computes actual DLQ depth by querying Kafka topic offsets via AdminClient.
     * DLQ depth = sum of latest offsets across all partitions of all DLQ topics.
     * Returns 0 on failure with a warning log (graceful degradation).
     */
    private long computeDlqDepth() {
        try {
            // First, discover which DLQ topics actually exist
            Set<String> existingTopics = adminClient.listTopics()
                    .names()
                    .get(5, TimeUnit.SECONDS);

            Map<TopicPartition, OffsetSpec> offsetRequests = new HashMap<>();

            for (String dlqTopic : DLQ_TOPICS) {
                if (!existingTopics.contains(dlqTopic)) {
                    continue;
                }
                // Get partition info for this topic
                adminClient.describeTopics(List.of(dlqTopic))
                        .topicNameValues()
                        .get(dlqTopic)
                        .get(5, TimeUnit.SECONDS)
                        .partitions()
                        .forEach(partitionInfo ->
                                offsetRequests.put(
                                        new TopicPartition(dlqTopic, partitionInfo.partition()),
                                        OffsetSpec.latest()
                                )
                        );
            }

            if (offsetRequests.isEmpty()) {
                return 0;
            }

            ListOffsetsResult offsetsResult = adminClient.listOffsets(offsetRequests);
            long totalDepth = 0;

            for (Map.Entry<TopicPartition, OffsetSpec> entry : offsetRequests.entrySet()) {
                try {
                    long offset = offsetsResult.partitionResult(entry.getKey())
                            .get(5, TimeUnit.SECONDS)
                            .offset();
                    totalDepth += offset;
                } catch (Exception e) {
                    log.debug("Failed to get offset for partition {}", entry.getKey(), e);
                }
            }

            return totalDepth;
        } catch (Exception e) {
            log.warn("Failed to compute DLQ depth metric, returning 0", e);
            return 0;
        }
    }
}
