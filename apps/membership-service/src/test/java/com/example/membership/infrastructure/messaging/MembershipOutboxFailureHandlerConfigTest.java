package com.example.membership.infrastructure.messaging;

import com.example.messaging.outbox.OutboxFailureHandler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MembershipOutboxFailureHandlerConfig} verifying that the
 * registered {@link OutboxFailureHandler} bean increments the
 * {@code membership_outbox_publish_failures} counter, tagged with the
 * {@code event_type} label, on each publish failure.
 *
 * <p>Plain JUnit 5 — uses {@link SimpleMeterRegistry} so counter values can be
 * asserted directly without Spring context or Mockito.
 */
@DisplayName("MembershipOutboxFailureHandlerConfig 단위 테스트")
class MembershipOutboxFailureHandlerConfigTest {

    private final MembershipOutboxFailureHandlerConfig config = new MembershipOutboxFailureHandlerConfig();

    @Test
    @DisplayName("outboxFailureHandler_singleFailure_incrementsCounterTaggedWithEventType")
    void outboxFailureHandler_singleFailure_incrementsCounterTaggedWithEventType() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxFailureHandler handler = config.outboxFailureHandler(registry);

        handler.onFailure("membership.subscription.activated", "sub-1", new RuntimeException("boom"));

        Counter counter = registry.find("membership_outbox_publish_failures")
                .tag("event_type", "membership.subscription.activated")
                .counter();
        assertThat(counter)
                .as("membership_outbox_publish_failures counter must be registered with event_type tag")
                .isNotNull();
        assertThat(counter.count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("outboxFailureHandler_multipleEventTypes_registersSeparateCountersPerTag")
    void outboxFailureHandler_multipleEventTypes_registersSeparateCountersPerTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxFailureHandler handler = config.outboxFailureHandler(registry);

        handler.onFailure("membership.subscription.activated", "sub-1", new RuntimeException("boom-1"));
        handler.onFailure("membership.subscription.activated", "sub-2", new RuntimeException("boom-2"));
        handler.onFailure("membership.subscription.expired", "sub-3", new RuntimeException("boom-3"));

        Counter activated = registry.find("membership_outbox_publish_failures")
                .tag("event_type", "membership.subscription.activated")
                .counter();
        Counter expired = registry.find("membership_outbox_publish_failures")
                .tag("event_type", "membership.subscription.expired")
                .counter();

        assertThat(activated).isNotNull();
        assertThat(activated.count()).isEqualTo(2.0d);
        assertThat(expired).isNotNull();
        assertThat(expired.count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("outboxFailureHandler_meterRegistryProvided_returnsNonNullHandler")
    void outboxFailureHandler_meterRegistryProvided_returnsNonNullHandler() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        OutboxFailureHandler handler = config.outboxFailureHandler(registry);

        assertThat(handler).isNotNull();
    }
}
