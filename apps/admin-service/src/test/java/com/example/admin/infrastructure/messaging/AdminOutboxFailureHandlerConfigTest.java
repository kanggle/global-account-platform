package com.example.admin.infrastructure.messaging;

import com.example.messaging.outbox.OutboxFailureHandler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AdminOutboxFailureHandlerConfig} verifying that the
 * registered {@link OutboxFailureHandler} bean increments the
 * {@code admin_outbox_publish_failures} counter, tagged with the
 * {@code event_type} label, on each publish failure.
 *
 * <p>Plain JUnit 5 — uses {@link SimpleMeterRegistry} so counter values can be
 * asserted directly without Spring context or Mockito.
 */
@DisplayName("AdminOutboxFailureHandlerConfig 단위 테스트")
class AdminOutboxFailureHandlerConfigTest {

    private final AdminOutboxFailureHandlerConfig config = new AdminOutboxFailureHandlerConfig();

    @Test
    @DisplayName("단일 publish 실패 시 event_type 태그와 함께 카운터가 1 증가한다")
    void outboxFailureHandler_singleFailure_incrementsCounterTaggedWithEventType() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxFailureHandler handler = config.outboxFailureHandler(registry);

        handler.onFailure("admin.action.performed", "agg-1", new RuntimeException("boom"));

        Counter counter = registry.find("admin_outbox_publish_failures")
                .tag("event_type", "admin.action.performed")
                .counter();
        assertThat(counter)
                .as("admin_outbox_publish_failures counter must be registered with event_type tag")
                .isNotNull();
        assertThat(counter.count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("여러 event_type으로 실패 시 태그별 독립 카운터가 각각 증가한다")
    void outboxFailureHandler_multipleEventTypes_registersSeparateCountersPerTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxFailureHandler handler = config.outboxFailureHandler(registry);

        handler.onFailure("admin.action.performed", "agg-1", new RuntimeException("boom-1"));
        handler.onFailure("admin.action.performed", "agg-2", new RuntimeException("boom-2"));
        handler.onFailure("admin.session.revoked", "agg-3", new RuntimeException("boom-3"));

        Counter performed = registry.find("admin_outbox_publish_failures")
                .tag("event_type", "admin.action.performed")
                .counter();
        Counter revoked = registry.find("admin_outbox_publish_failures")
                .tag("event_type", "admin.session.revoked")
                .counter();

        assertThat(performed).isNotNull();
        assertThat(performed.count()).isEqualTo(2.0d);
        assertThat(revoked).isNotNull();
        assertThat(revoked.count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("MeterRegistry를 주입하면 null이 아닌 OutboxFailureHandler를 반환한다")
    void outboxFailureHandler_meterRegistryProvided_returnsNonNullHandler() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        OutboxFailureHandler handler = config.outboxFailureHandler(registry);

        assertThat(handler).isNotNull();
    }
}
