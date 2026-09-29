package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.dispatch.core.handler.InMemoryJobHandlerRegistry;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.retry.RetryPolicy;
import dev.dispatch.core.store.JobStore;
import dev.dispatch.core.testing.MutableClock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A late result from an earlier attempt must not land on a later attempt of the same job, even
 * when the same instance ran both.
 *
 * <p>There is no lease heartbeat, so a handler that outruns its visibility timeout is reclaimed
 * while it is still running, and the very same instance can claim the job again as attempt 2. The
 * lease was keyed on the worker id alone, which both attempts share, so attempt 1's eventual
 * failure (or success) was accepted against attempt 2's lease: the job was failed or completed
 * while attempt 2 was still running, and the lost lease was counted against the wrong attempt.
 */
@DisplayName("A stale attempt's outcome")
class StaleAttemptOutcomeTest {

    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private final MutableClock clock = MutableClock.atEpoch();
    private final JobStore store = JobStore.inMemory();
    private final CountDownLatch attempt1Started = new CountDownLatch(1);
    private final CountDownLatch attempt2Started = new CountDownLatch(1);
    private final CountDownLatch releaseAttempt1 = new CountDownLatch(1);
    private final CountDownLatch releaseAttempt2 = new CountDownLatch(1);

    /** Attempt 1 fails or succeeds when released, attempt 2 succeeds when released. */
    private JobQueue queue(boolean attempt1Fails) {
        InMemoryJobHandlerRegistry registry = new InMemoryJobHandlerRegistry();
        registry.register("slow", context -> {
            if (context.attempt() == 1) {
                attempt1Started.countDown();
                releaseAttempt1.await();
                if (attempt1Fails) {
                    throw new IllegalStateException("attempt 1 finally fails, after its lease expired");
                }
                return;
            }
            attempt2Started.countDown();
            releaseAttempt2.await();
        });
        return JobQueue.builder().store(store).registry(registry).clock(clock)
                .retryPolicy(RetryPolicy.immediate())
                .config(QueueConfig.builder().workerId("only-instance").concurrency(4)
                        .visibilityTimeout(LEASE).build())
                .build();
    }

    /** Runs attempt 1 past its lease, has the sweeper reclaim it and the same instance re-claim it. */
    private WorkerPool.DispatchResult stallAttempt1ThenClaimAttempt2(JobQueue queue, Job job)
            throws Exception {
        WorkerPool.DispatchResult first = queue.dispatchOnce();
        assertThat(attempt1Started.await(5, TimeUnit.SECONDS)).isTrue();

        clock.advance(LEASE.plusSeconds(1));
        assertThat(queue.sweep().reclaimed()).isEqualTo(1);
        queue.dispatchOnce();
        assertThat(attempt2Started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(store.find(job.id()).orElseThrow().attempt()).isEqualTo(2);
        return first;
    }

    @Test
    @DisplayName("a late failure from attempt 1 cannot fail attempt 2")
    void lateFailureIsRejected() throws Exception {
        JobQueue queue = queue(true);
        try {
            Job job = queue.submit(JobSubmission.of("slow", "{}"));
            WorkerPool.DispatchResult first = stallAttempt1ThenClaimAttempt2(queue, job);

            releaseAttempt1.countDown();
            assertThat(first.awaitCompletion(PATIENCE)).isTrue();

            Job seen = store.find(job.id()).orElseThrow();
            assertThat(seen.state()).as("attempt 2 is still running").isEqualTo(JobState.RUNNING);
            assertThat(seen.attempt()).isEqualTo(2);
            assertThat(queue.metrics().leasesLost()).isEqualTo(1);
            assertThat(queue.metrics().retriesScheduled()).isZero();

            releaseAttempt2.countDown();
            await().atMost(PATIENCE).untilAsserted(() ->
                    assertThat(store.find(job.id()).orElseThrow().state())
                            .isEqualTo(JobState.COMPLETED));
            assertThat(queue.metrics().succeeded()).isEqualTo(1);
        } finally {
            releaseAttempt1.countDown();
            releaseAttempt2.countDown();
            queue.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    @DisplayName("a late success from attempt 1 cannot complete attempt 2")
    void lateSuccessIsRejected() throws Exception {
        JobQueue queue = queue(false);
        try {
            Job job = queue.submit(JobSubmission.of("slow", "{}"));
            WorkerPool.DispatchResult first = stallAttempt1ThenClaimAttempt2(queue, job);

            releaseAttempt1.countDown();
            assertThat(first.awaitCompletion(PATIENCE)).isTrue();

            assertThat(store.find(job.id()).orElseThrow().state())
                    .as("attempt 2 is still running").isEqualTo(JobState.RUNNING);
            assertThat(queue.metrics().leasesLost()).isEqualTo(1);
            assertThat(queue.metrics().succeeded()).as("the stale success is not counted").isZero();
        } finally {
            releaseAttempt1.countDown();
            releaseAttempt2.countDown();
            queue.shutdown(Duration.ofSeconds(5));
        }
    }
}
