package com.example.security.infrastructure.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that SecurityMetricsConfig registers per-partition
 * {@code kafka.consumer.lag} gauges tagged with topic/group/partition,
 * derived from the Kafka client {@code records-lag} metric on each
 * listener container.
 */
class SecurityMetricsConfigTest {

    @Test
    void refreshConsumerLag_registersPerPartitionGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KafkaListenerEndpointRegistry listenerRegistry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);

        MetricName lagMetric = new MetricName(
                "records-lag",
                "consumer-fetch-manager-metrics",
                "lag",
                Map.of(
                        "client-id", "consumer-security-service-1",
                        "topic", "auth.login.succeeded",
                        "partition", "0"
                )
        );
        Metric metricStub = mock(Metric.class);
        when(metricStub.metricValue()).thenReturn(42.0);

        when(container.metrics()).thenReturn(Map.of(
                "consumer-security-service-1",
                Map.of(lagMetric, metricStub)
        ));
        when(listenerRegistry.getListenerContainers()).thenReturn(List.<MessageListenerContainer>of(container));

        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.getConsumer().setGroupId("security-service");

        SecurityMetricsConfig config = new SecurityMetricsConfig(registry, listenerRegistry, kafkaProperties);
        try {
            config.refreshConsumerLag();

            Meter meter = registry.find("kafka.consumer.lag")
                    .tag("topic", "auth.login.succeeded")
                    .tag("group", "security-service")
                    .tag("partition", "0")
                    .meter();
            assertThat(meter).as("kafka.consumer.lag gauge registered").isNotNull();
            double value = registry.find("kafka.consumer.lag")
                    .tag("partition", "0")
                    .gauge()
                    .value();
            assertThat(value).isEqualTo(42.0d);
        } finally {
            config.closeAdminClient();
        }
    }
}
