package com.example.security.infrastructure.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.*;

@Slf4j
@Configuration
public class SecurityMetricsConfig {

    private final MeterRegistry meterRegistry;
    private final KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    private static final List<String> DLQ_TOPICS = List.of(
            "auth.login.attempted.dlq",
            "auth.login.failed.dlq",
            "auth.login.succeeded.dlq",
            "auth.token.refreshed.dlq",
            "auth.token.reuse.detected.dlq"
    );

    public SecurityMetricsConfig(MeterRegistry meterRegistry,
                                  KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry) {
        this.meterRegistry = meterRegistry;
        this.kafkaListenerEndpointRegistry = kafkaListenerEndpointRegistry;

        Gauge.builder("security_consumer_lag", this, SecurityMetricsConfig::computeConsumerLag)
                .description("Total consumer lag across all partitions")
                .register(meterRegistry);

        Gauge.builder("security_dlq_depth", this, SecurityMetricsConfig::computeDlqDepth)
                .description("Total DLQ depth across all DLQ topics")
                .register(meterRegistry);
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

    private double computeDlqDepth() {
        // DLQ depth is tracked via the DLQ consumer or external monitoring.
        // This is a placeholder that returns 0; real DLQ depth monitoring
        // requires a separate admin client query or consumer group offset check.
        return 0;
    }
}
