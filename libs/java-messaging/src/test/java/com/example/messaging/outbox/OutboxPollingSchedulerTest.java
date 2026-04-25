package com.example.messaging.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link OutboxPollingScheduler} lifecycle (TASK-BE-079).
 *
 * <p>Verifies that:
 * <ul>
 *   <li>{@link OutboxPollingScheduler#start()} registers a fixed-delay task on
 *       the injected {@link ThreadPoolTaskScheduler}.</li>
 *   <li>{@link OutboxPollingScheduler#stop()} cancels the returned
 *       {@link ScheduledFuture}.</li>
 *   <li>Once stopped, {@link OutboxPollingScheduler#pollAndPublish()} returns
 *       early without touching {@link OutboxPublisher}.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("OutboxPollingScheduler 수명주기 단위 테스트")
class OutboxPollingSchedulerTest {

    @Mock
    private OutboxPublisher outboxPublisher;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private ThreadPoolTaskScheduler taskScheduler;

    @Mock
    private ScheduledFuture<?> scheduledFuture;

    private TestScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new TestScheduler(outboxPublisher, kafkaTemplate, taskScheduler);
        ReflectionTestUtils.setField(scheduler, "intervalMs", 1000L);
    }

    @Test
    @DisplayName("start() 호출 시 taskScheduler.scheduleWithFixedDelay 로 폴링 태스크를 등록한다")
    void start_registersFixedDelayTask() {
        given(taskScheduler.scheduleWithFixedDelay(any(Runnable.class), any(Duration.class)))
                .willAnswer(invocation -> scheduledFuture);

        scheduler.start();

        verify(taskScheduler).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
    }

    @Test
    @DisplayName("stop() 호출 시 ScheduledFuture.cancel(false) 로 태스크를 취소한다")
    void stop_cancelsScheduledFuture() {
        given(taskScheduler.scheduleWithFixedDelay(any(Runnable.class), any(Duration.class)))
                .willAnswer(invocation -> scheduledFuture);
        scheduler.start();

        scheduler.stop();

        verify(scheduledFuture).cancel(false);
    }

    @Test
    @DisplayName("running=false 상태에서 pollAndPublish() 는 조기 반환하여 publisher 를 호출하지 않는다")
    void pollAndPublish_afterStop_returnsEarly() {
        given(taskScheduler.scheduleWithFixedDelay(any(Runnable.class), any(Duration.class)))
                .willAnswer(invocation -> scheduledFuture);
        scheduler.start();
        scheduler.stop();

        scheduler.pollAndPublish();

        verifyNoInteractions(outboxPublisher);
    }

    /**
     * Minimal concrete subclass exposed for the abstract {@link OutboxPollingScheduler}.
     * {@link #resolveTopic(String)} is never invoked by these tests — all
     * lifecycle assertions target the base class behaviour.
     */
    static class TestScheduler extends OutboxPollingScheduler {
        TestScheduler(OutboxPublisher publisher,
                      KafkaTemplate<String, String> kafka,
                      ThreadPoolTaskScheduler scheduler) {
            super(publisher, kafka, scheduler);
        }

        @Override
        protected String resolveTopic(String eventType) {
            return "test-topic";
        }
    }
}
