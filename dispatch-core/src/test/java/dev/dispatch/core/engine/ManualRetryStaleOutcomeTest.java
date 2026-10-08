package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dispatch.core.handler.InMemoryJobHandlerRegistry;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobActionResult;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.retry.RetryPolicy;
import dev.dispatch.core.store.JobStore;
import dev.dispatch.core.testing.MutableClock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A late result from a stalled attempt must not land on the claim that followed a manual retry.
 *
 * <p>A manual retry resets the attempt count, so the claim after it is attempt 1 again. With
 * {@code maxRetries = 0}, a stalled first run is dead-lettered by the sweep, an operator retries
 * the job, and the same instance claims it again as attempt 1. Both claims then had the same job,
 * worker and attempt, which was all the lease named. So the stalled run's result was accepted
 * against the new claim. A late failure dead-lettered the job again, undoing the operator's retry,
 * and the new claim's own result was then refused as a lost lease.
 */
@DisplayName("A stale outcome after a manual retry")
class ManualRetryStaleOutcomeTest {

    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private final MutableClock clock = MutableClock.atEpoch();
    private final JobStore store = JobStore.inMemory();
    private final AtomicInteger runs = new AtomicInteger();
    private final CountDownLatch firstRunStarted = new CountDownLatch(1);
    private final CountDownLatch secondRunStarted = new CountDownLatch(1);
    private final CountDownLatch releaseFirstRun = new CountDownLatch(1);
    private final CountDownLatch releaseSecondRun = new CountDownLatch(1);

    /**
     * The first run fails or succeeds when released, and the second succeeds when released. Runs
     * are told apart by count, since both are attempt 1.
     */
    private JobQueue queue(boolean firstRunFails) {
        InMemoryJobHandlerRegistry registry = new InMemoryJobHandlerRegistry();
        registry.register("slow", context -> {
            if (runs.incrementAndGet() == 1) {
                firstRunStarted.countDown();
                releaseFirstRun.await();
                if (firstRunFails) {
                    throw new IllegalStateException("the first run finally fails, after its lease expired");
                }
                return;
            }
            secondRunStarted.countDown();
            releaseSecondRun.await();
        });
        // Concurrency 2: the stalled first run still holds a permit when the second claim is made.
        return JobQueue.builder().store(store).registry(registry).clock(clock)
                .retryPolicy(RetryPolicy.immediate())
                .config(QueueConfig.builder().workerId("only-instance").concurrency(2)
                        .visibilityTimeout(LEASE).build())
                .build();
    }

    /** The cycle that claimed the stalled first run, and the one that claimed the job after the retry. */
    private record Cycles(WorkerPool.DispatchResult first, WorkerPool.DispatchResult second) {
    }

    /**
     * Stalls the first run past its lease so the sweep dead-letters the job, retries it by hand and
     * has the same instance claim it again.
     */
    private Cycles stallThenRetryByHandAndReclaim(JobQueue queue, Job job) throws Exception {
        WorkerPool.DispatchResult first = queue.dispatchOnce();
        assertThat(first.dispatched()).extracting(Job::id).containsExactly(job.id());
        assertThat(firstRunStarted.await(5, TimeUnit.SECONDS)).isTrue();

        clock.advance(LEASE.plusSeconds(1));
        assertThat(queue.sweep().reclaimed()).isEqualTo(1);
        assertThat(store.find(job.id()).orElseThrow().state()).isEqualTo(JobState.DEAD);
        assertThat(queue.retryDeadJob(job.id())).isInstanceOf(JobActionResult.Done.class);

        WorkerPool.DispatchResult second = queue.dispatchOnce();
        assertThat(second.dispatched()).extracting(Job::id).containsExactly(job.id());
        Job firstClaim = first.dispatched().get(0);
        Job secondClaim = second.dispatched().get(0);
        // Same job, same worker, same attempt number: only the claim tells them apart.
        assertThat(secondClaim.attempt()).isEqualTo(firstClaim.attempt()).isEqualTo(1);
        assertThat(secondClaim.lockedBy()).isEqualTo(firstClaim.lockedBy());
        assertThat(secondRunStarted.await(5, TimeUnit.SECONDS)).isTrue();
        return new Cycles(first, second);
    }

    @Test
    @DisplayName("a late failure from the stalled run cannot dead-letter the retried job again")
    void lateFailureIsRejected() throws Exception {
        JobQueue queue = queue(true);
        try {
            Job job = queue.submit(new JobSubmission("slow", "{}", 0, 0, null));
            Cycles cycles = stallThenRetryByHandAndReclaim(queue, job);
            Job secondClaim = cycles.second().dispatched().get(0);

            releaseFirstRun.countDown();
            assertThat(cycles.first().awaitCompletion(PATIENCE)).isTrue();

            Job seen = store.find(job.id()).orElseThrow();
            assertThat(seen.state()).as("the retried job is still running").isEqualTo(JobState.RUNNING);
            assertThat(seen.heldUnder(secondClaim.lease())).isTrue();
            assertThat(queue.metrics().leasesLost()).isEqualTo(1);
            assertThat(queue.metrics().deadLettered()).isZero();

            releaseSecondRun.countDown();
            assertThat(cycles.second().awaitCompletion(PATIENCE)).isTrue();
            assertThat(store.find(job.id()).orElseThrow().state()).isEqualTo(JobState.COMPLETED);
            assertThat(queue.metrics().succeeded()).isEqualTo(1);
            assertThat(queue.metrics().leasesLost()).isEqualTo(1);
        } finally {
            releaseFirstRun.countDown();
            releaseSecondRun.countDown();
            queue.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    @DisplayName("a late success from the stalled run cannot complete the retried job")
    void lateSuccessIsRejected() throws Exception {
        JobQueue queue = queue(false);
        try {
            Job job = queue.submit(new JobSubmission("slow", "{}", 0, 0, null));
            Cycles cycles = stallThenRetryByHandAndReclaim(queue, job);
            Job secondClaim = cycles.second().dispatched().get(0);

            releaseFirstRun.countDown();
            assertThat(cycles.first().awaitCompletion(PATIENCE)).isTrue();

            Job seen = store.find(job.id()).orElseThrow();
            assertThat(seen.state()).as("the retried job is still running").isEqualTo(JobState.RUNNING);
            assertThat(seen.heldUnder(secondClaim.lease())).isTrue();
            assertThat(queue.metrics().leasesLost()).isEqualTo(1);
            assertThat(queue.metrics().succeeded()).as("the stale success is not counted").isZero();

            releaseSecondRun.countDown();
            assertThat(cycles.second().awaitCompletion(PATIENCE)).isTrue();
            assertThat(store.find(job.id()).orElseThrow().state()).isEqualTo(JobState.COMPLETED);
            assertThat(queue.metrics().succeeded()).isEqualTo(1);
            assertThat(queue.metrics().leasesLost()).isEqualTo(1);
        } finally {
            releaseFirstRun.countDown();
            releaseSecondRun.countDown();
            queue.shutdown(Duration.ofSeconds(5));
        }
    }
}
