package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.dispatch.core.handler.InMemoryJobHandlerRegistry;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.retry.RetryPolicy;
import dev.dispatch.core.store.memory.InMemoryJobStore;
import dev.dispatch.core.testing.MutableClock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Graceful shutdown: stop claiming, drain what is in flight, interrupt the stragglers.
 *
 * <p>These tests use real sleeps rather than the mutable clock, because what is under test is
 * genuine wall-clock draining behaviour rather than a scheduling decision.
 */
@DisplayName("Graceful shutdown")
class GracefulShutdownTest {

    private MutableClock clock;
    private InMemoryJobStore store;
    private InMemoryJobHandlerRegistry registry;

    @BeforeEach
    void setUp() {
        clock = MutableClock.atEpoch();
        store = new InMemoryJobStore();
        registry = new InMemoryJobHandlerRegistry();
    }

    private JobQueue startQueue(int concurrency) {
        return JobQueue.builder()
                .store(store)
                .registry(registry)
                .clock(clock)
                .retryPolicy(RetryPolicy.immediate())
                .config(QueueConfig.builder()
                        .workerId("test-worker")
                        .concurrency(concurrency)
                        .claimBatchSize(2)
                        .pollInterval(Duration.ofMillis(20))
                        .maintenanceInterval(Duration.ofMillis(20))
                        .visibilityTimeout(Duration.ofMinutes(5))
                        .build())
                .build()
                .start();
    }

    @Test
    @DisplayName("in-flight jobs finish before shutdown returns")
    void inFlightJobsAreDrained() throws Exception {
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch allStarted = new CountDownLatch(4);
        registry.register("slow", context -> {
            allStarted.countDown();
            Thread.sleep(300);
            completed.incrementAndGet();
        });

        JobQueue queue = startQueue(4);
        for (int i = 0; i < 4; i++) {
            queue.submit("slow", "{}");
        }
        assertThat(allStarted.await(10, TimeUnit.SECONDS)).isTrue();

        boolean drained = queue.shutdown(Duration.ofSeconds(10));

        assertThat(drained).isTrue();
        assertThat(completed).hasValue(4);
        // Draining means results are recorded, not merely that the threads exited.
        assertThat(store.countsByState().get(JobState.COMPLETED)).isEqualTo(4);
        assertThat(queue.isRunning()).isFalse();
    }

    @Test
    @DisplayName("no new jobs are claimed once shutdown begins")
    void stopsClaimingImmediately() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        registry.register("slow", context -> {
            started.countDown();
            Thread.sleep(200);
        });

        JobQueue queue = startQueue(2);
        for (int i = 0; i < 30; i++) {
            queue.submit("slow", "{}");
        }
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        queue.shutdown(Duration.ofSeconds(10));

        Map<JobState, Long> counts = store.countsByState();
        // The backlog is untouched and, crucially, still claimable by another instance.
        assertThat(counts.get(JobState.PENDING)).isPositive();
        assertThat(counts.get(JobState.RUNNING)).isZero();
        assertThat(counts.get(JobState.COMPLETED) + counts.get(JobState.PENDING)).isEqualTo(30);
        assertThat(queue.metrics().claimed()).isLessThan(30);
    }

    @Test
    @DisplayName("a job still running at the deadline is interrupted and requeued, not lost")
    void deadlineInterruptsStragglers() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger interrupted = new AtomicInteger();
        registry.register("very-slow", context -> {
            started.countDown();
            try {
                Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                interrupted.incrementAndGet();
                throw e;
            }
        });

        JobQueue queue = startQueue(1);
        Job job = queue.submit(new JobSubmission("very-slow", "{}", 0, 3, null));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        long elapsedMillis = System.nanoTime();
        boolean drained = queue.shutdown(Duration.ofMillis(200));
        elapsedMillis = Duration.ofNanos(System.nanoTime() - elapsedMillis).toMillis();

        assertThat(drained).as("the job could not finish within the deadline").isFalse();
        assertThat(interrupted).hasValue(1);
        // Shutdown honoured its deadline rather than waiting out the 30s handler.
        assertThat(elapsedMillis).isLessThan(10_000);

        // The interrupted attempt was recorded as a failure, so the job goes back on the queue
        // for another instance to pick up. Nothing is silently dropped.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(store.find(job.id()).orElseThrow().state())
                        .isIn(JobState.FAILED, JobState.PENDING));
        assertThat(store.find(job.id()).orElseThrow().attempt()).isEqualTo(1);
    }

    @Test
    @DisplayName("shutting down an already stopped queue is harmless")
    void shutdownIsIdempotent() {
        registry.register("noop", context -> { });
        JobQueue queue = startQueue(2);

        assertThat(queue.shutdown(Duration.ofSeconds(5))).isTrue();
        assertThat(queue.shutdown(Duration.ofSeconds(5))).isTrue();
        assertThat(queue.isRunning()).isFalse();
    }

    @Test
    @DisplayName("try-with-resources drains the queue on the way out")
    void closeDrainsQueue() throws Exception {
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        registry.register("slow", context -> {
            started.countDown();
            Thread.sleep(200);
            completed.incrementAndGet();
        });

        try (JobQueue queue = JobQueue.builder()
                .store(store)
                .registry(registry)
                .clock(clock)
                .config(QueueConfig.builder()
                        .workerId("test-worker")
                        .concurrency(2)
                        .pollInterval(Duration.ofMillis(20))
                        .maintenanceInterval(Duration.ofMillis(20))
                        .shutdownDrainTimeout(Duration.ofSeconds(10))
                        .build())
                .build()
                .start()) {
            queue.submit("slow", "{}");
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(completed).hasValue(1);
        assertThat(store.countsByState().get(JobState.COMPLETED)).isEqualTo(1);
    }

    @Test
    @DisplayName("a queue that was never started shuts down cleanly")
    void shutdownWithoutStart() {
        JobQueue queue = JobQueue.builder()
                .store(store)
                .registry(registry)
                .clock(clock)
                .build();

        assertThat(queue.shutdown(Duration.ofSeconds(1))).isTrue();
    }
}
