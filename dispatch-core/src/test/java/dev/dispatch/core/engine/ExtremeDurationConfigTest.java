package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import dev.dispatch.core.handler.InMemoryJobHandlerRegistry;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.retry.RetryPolicy;
import dev.dispatch.core.store.JobStore;
import dev.dispatch.core.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Durations at either end of the range must not stop the engine from starting or from running.
 *
 * <p>{@link QueueConfig} accepts any positive duration, and Spring binds {@code 500us} or
 * {@code 400000d} as readily as {@code 250ms}. Converting those to a scheduler's units overflowed
 * or truncated to zero, and the exceptions escaped the threads that did the conversion.
 */
@DisplayName("Extreme duration settings")
class ExtremeDurationConfigTest {

    /** About a thousand years: more than Duration.toNanos() can represent (roughly 292). */
    private static final Duration ABSURDLY_LONG = Duration.ofDays(365_000);

    @Test
    @DisplayName("a poll interval too long for nanoseconds means 'wait for a wake-up', not a dead dispatcher")
    void hugePollIntervalDoesNotKillTheDispatcher() {
        JobStore store = JobStore.inMemory();
        InMemoryJobHandlerRegistry registry = new InMemoryJobHandlerRegistry();
        registry.register("record", context -> { });
        WorkerPool pool = new WorkerPool(store, registry, RetryPolicy.immediate(),
                QueueConfig.builder().workerId("huge-poll").concurrency(2)
                        .pollInterval(ABSURDLY_LONG).build(),
                new QueueMetrics(), MutableClock.atEpoch());
        try {
            pool.start();
            Job job = store.insert(new JobSubmission("record", "{}", 0, 3, null), Instant.EPOCH);

            // The dispatcher parks after its first empty cycle for "a thousand years". A wake-up
            // (what a local submit sends) must still reach it, so it has to be alive and parked
            // rather than dead of an ArithmeticException.
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                pool.wakeUp();
                assertThat(store.find(job.id()).orElseThrow().state())
                        .isEqualTo(JobState.COMPLETED);
            });
        } finally {
            pool.close();
        }
    }

    @Test
    @DisplayName("a maintenance interval under one millisecond starts, instead of failing after the pool started")
    void subMillisecondMaintenanceIntervalStarts() {
        JobStore store = JobStore.inMemory();
        InMemoryJobHandlerRegistry registry = new InMemoryJobHandlerRegistry();
        registry.register("record", context -> { });
        JobQueue queue = JobQueue.builder().store(store).registry(registry)
                .config(QueueConfig.builder().workerId("sub-ms").concurrency(1)
                        .maintenanceInterval(Duration.ofNanos(500_000)).build())
                .build();
        try {
            assertThatCode(queue::start).doesNotThrowAnyException();
            assertThat(queue.isRunning()).isTrue();

            // The sweeper is actually running: only it can promote a delayed job.
            Job job = queue.submit(
                    JobSubmission.delayed("record", "{}", Duration.ofMillis(100), Instant.now()));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(store.find(job.id()).orElseThrow().state())
                            .isEqualTo(JobState.COMPLETED));
        } finally {
            queue.shutdown(Duration.ofSeconds(5));
        }
    }
}
