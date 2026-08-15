package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.dispatch.core.handler.InMemoryJobHandlerRegistry;
import dev.dispatch.core.handler.JobHandler;
import dev.dispatch.core.handler.PermanentJobFailureException;
import dev.dispatch.core.handler.UnknownJobTypeException;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobActionResult;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.retry.RetryPolicy;
import dev.dispatch.core.store.JobStore;
import dev.dispatch.core.testing.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Retries, backoff and the dead-letter state, end to end through the worker pool.
 *
 * <p>The clock is frozen and driven by hand, so "wait out the backoff" is an assignment rather
 * than a sleep. Maintenance runs on a 20ms timer, so once the clock says a job is due the sweeper
 * picks it up almost immediately — fast tests that still exercise the real promotion path.
 */
@DisplayName("Retries, backoff and dead-lettering")
class RetryAndDeadLetterTest {

    private MutableClock clock;
    private JobStore store;
    private InMemoryJobHandlerRegistry registry;
    private JobQueue queue;

    @BeforeEach
    void setUp() {
        clock = MutableClock.atEpoch();
        store = JobStore.inMemory();
        registry = new InMemoryJobHandlerRegistry();
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.shutdown(Duration.ofSeconds(10));
        }
    }

    private JobQueue startQueue(RetryPolicy retryPolicy) {
        queue = JobQueue.builder()
                .store(store)
                .registry(registry)
                .retryPolicy(retryPolicy)
                .clock(clock)
                .config(QueueConfig.builder()
                        .workerId("test-worker")
                        .concurrency(4)
                        .pollInterval(Duration.ofMillis(20))
                        .maintenanceInterval(Duration.ofMillis(20))
                        .visibilityTimeout(Duration.ofMinutes(5))
                        .build())
                .build()
                .start();
        return queue;
    }

    @Test
    @DisplayName("a job that fails twice and then succeeds is retried until it works")
    void retriesUntilSuccess() {
        AtomicInteger attempts = new AtomicInteger();
        registry.register("flaky", context -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("transient failure #" + attempts.get());
            }
        });
        startQueue(RetryPolicy.immediate());

        Job job = queue.submit("flaky", "{}");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(store.find(job.id()).orElseThrow().state())
                        .isEqualTo(JobState.COMPLETED));

        assertThat(attempts).hasValue(3);
        assertThat(store.find(job.id()).orElseThrow().attempt()).isEqualTo(3);
        assertThat(queue.metrics().retriesScheduled()).isEqualTo(2);
        assertThat(queue.metrics().succeeded()).isEqualTo(1);
        assertThat(queue.metrics().failedAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("a job that exhausts its retries lands in the dead-letter state")
    void exhaustedRetriesGoToDead() {
        AtomicInteger attempts = new AtomicInteger();
        registry.register("always-fails", context -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("nope");
        });
        startQueue(RetryPolicy.immediate());

        Job job = queue.submit(new JobSubmission("always-fails", "{}", 0, 2, null));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(store.find(job.id()).orElseThrow().state()).isEqualTo(JobState.DEAD));

        Job dead = store.find(job.id()).orElseThrow();
        // maxRetries=2 means one initial attempt plus two retries.
        assertThat(attempts).hasValue(3);
        assertThat(dead.attempt()).isEqualTo(3);
        assertThat(dead.lastError()).contains("nope");
        assertThat(dead.lockedBy()).isNull();
        assertThat(queue.metrics().deadLettered()).isEqualTo(1);
    }

    @Test
    @DisplayName("a permanent failure skips the retry budget entirely")
    void permanentFailureSkipsRetries() {
        AtomicInteger attempts = new AtomicInteger();
        registry.register("bad-payload", context -> {
            attempts.incrementAndGet();
            throw new PermanentJobFailureException("payload is malformed; retrying cannot help");
        });
        startQueue(RetryPolicy.immediate());

        Job job = queue.submit(new JobSubmission("bad-payload", "{}", 0, 5, null));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(store.find(job.id()).orElseThrow().state()).isEqualTo(JobState.DEAD));

        // Five retries were available and none were spent.
        assertThat(attempts).hasValue(1);
        assertThat(store.find(job.id()).orElseThrow().attempt()).isEqualTo(1);
    }

    @Test
    @DisplayName("backoff actually holds the retry back until its time comes")
    void backoffDelaysTheRetry() {
        List<java.time.Instant> attemptTimes = new CopyOnWriteArrayList<>();
        registry.register("slow-retry", context -> {
            attemptTimes.add(clock.instant());
            throw new IllegalStateException("boom");
        });
        startQueue(RetryPolicy.fixed(Duration.ofSeconds(30)));

        Job job = queue.submit(new JobSubmission("slow-retry", "{}", 0, 1, null));

        // First attempt happens, then the job parks in FAILED with a 30s backoff.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(store.find(job.id()).orElseThrow().state()).isEqualTo(JobState.FAILED));
        Job failed = store.find(job.id()).orElseThrow();
        assertThat(failed.scheduledAt()).isEqualTo(clock.instant().plusSeconds(30));
        assertThat(attemptTimes).hasSize(1);

        // No amount of sweeping moves it while the backoff is unexpired.
        assertThat(queue.sweep().promoted()).isZero();
        assertThat(store.find(job.id()).orElseThrow().state()).isEqualTo(JobState.FAILED);

        // Move the clock past the backoff and the retry runs.
        clock.advance(Duration.ofSeconds(30));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(store.find(job.id()).orElseThrow().state()).isEqualTo(JobState.DEAD));
        assertThat(attemptTimes).hasSize(2);
    }

    @Test
    @DisplayName("a dead job can be revived by hand and then succeeds")
    void deadJobCanBeRevived() {
        AtomicInteger attempts = new AtomicInteger();
        // Fails while the downstream service is down...
        JobHandler broken = context -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("downstream is down");
        };
        registry.register("recoverable", broken);
        startQueue(RetryPolicy.immediate());

        Job job = queue.submit(new JobSubmission("recoverable", "{}", 0, 1, null));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(store.find(job.id()).orElseThrow().state()).isEqualTo(JobState.DEAD));
        assertThat(attempts).hasValue(2);

        // ...the service comes back, and an operator requeues the job.
        registry.replace("recoverable", context -> { });
        assertThat(queue.retryDeadJob(job.id())).isInstanceOf(JobActionResult.Done.class);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(store.find(job.id()).orElseThrow().state())
                        .isEqualTo(JobState.COMPLETED));
        // The revived job got a full retry budget rather than one last chance.
        assertThat(store.find(job.id()).orElseThrow().attempt()).isEqualTo(1);
    }

    @Test
    @DisplayName("reviving an unknown job reports not found")
    void revivingLiveJobIsRefused() {
        registry.register("noop", context -> { });
        startQueue(RetryPolicy.immediate());

        assertThat(queue.retryDeadJob(UUID.randomUUID()))
                .isInstanceOf(JobActionResult.NotFound.class);
    }

    @Test
    @DisplayName("submitting a job type with no registered handler is refused at the door")
    void unknownJobTypeIsRefusedAtSubmission() {
        startQueue(RetryPolicy.immediate());

        assertThatThrownBy(() -> queue.submit(new JobSubmission("nobody-handles-this", "{}", 0, 1, null)))
                .isInstanceOf(UnknownJobTypeException.class)
                .hasMessageContaining("nobody-handles-this");
    }

    @Test
    @DisplayName("a claimed job whose handler is missing here is retried, then dead-lettered")
    void unknownJobTypeAtExecutionEventuallyDies() {
        // Another instance submitted this (simulated by inserting straight into the shared
        // store); this instance claims it without having the handler. Per ADR-0001 that is
        // retryable — a rolling deploy may put the handler on a peer — until the budget runs out.
        startQueue(RetryPolicy.immediate());

        Job job = store.insert(new JobSubmission("nobody-handles-this", "{}", 0, 1, null),
                clock.instant());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(store.find(job.id()).orElseThrow().state()).isEqualTo(JobState.DEAD));
        assertThat(store.find(job.id()).orElseThrow().lastError())
                .contains("No handler registered");
    }

    @Test
    @DisplayName("the failure rate reflects attempts, not jobs")
    void failureRateCountsAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        registry.register("flaky", context -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("transient");
            }
        });
        startQueue(RetryPolicy.immediate());

        Job job = queue.submit("flaky", "{}");
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(store.find(job.id()).orElseThrow().state())
                        .isEqualTo(JobState.COMPLETED));

        QueueStats stats = queue.stats();
        // Two failed attempts and one success: one job, three attempts.
        assertThat(stats.instanceMetrics().failedAttempts()).isEqualTo(2);
        assertThat(stats.instanceMetrics().succeeded()).isEqualTo(1);
        assertThat(stats.instanceMetrics().failureRate())
                .isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(stats.depth(JobState.COMPLETED)).isEqualTo(1);
    }
}
