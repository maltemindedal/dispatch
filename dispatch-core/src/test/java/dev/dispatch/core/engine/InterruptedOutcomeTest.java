package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dispatch.core.handler.InMemoryJobHandlerRegistry;
import dev.dispatch.core.handler.PermanentJobFailureException;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.retry.RetryPolicy;
import dev.dispatch.core.store.JobRows;
import dev.dispatch.core.store.JobStore;
import dev.dispatch.core.store.JobStoreException;
import dev.dispatch.core.store.memory.InMemoryJobRows;
import dev.dispatch.core.testing.MutableClock;
import java.time.Duration;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A handler's outcome is recorded with the thread's interrupt flag clear.
 *
 * <p>Interrupt handling in handlers is often imperfect: the standard idiom is to restore the flag
 * and then throw, and some code swallows the interrupt and returns normally. Either leaves the flag
 * set on the worker thread when the engine goes to record the result, and JDBC calls made with the
 * flag set fail (a pool's wait for a connection throws, and a socket read on a virtual thread is
 * closed by the interrupt). The failure to record was then only logged, so the job stayed RUNNING
 * until its lease ran out: a needless delay for a failure, a duplicate run for a success.
 *
 * <p>The store here refuses any call made with the flag set, as those drivers do.
 */
@DisplayName("Recording an outcome after an interrupt")
class InterruptedOutcomeTest {

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    /** Delegates to memory, but fails like a JDBC store does when the caller is interrupted. */
    private static final class RejectsInterruptedCallers implements JobRows {

        private final InMemoryJobRows delegate = new InMemoryJobRows();

        @Override
        public <R> R inExclusiveScope(Function<Scope, R> work) {
            if (Thread.currentThread().isInterrupted()) {
                throw new JobStoreException("Interrupted during connection acquisition");
            }
            return delegate.inExclusiveScope(work);
        }
    }

    private MutableClock clock;
    private JobStore store;
    private InMemoryJobHandlerRegistry registry;
    private WorkerPool pool;

    @BeforeEach
    void setUp() {
        clock = MutableClock.atEpoch();
        store = JobStore.over(new RejectsInterruptedCallers());
        registry = new InMemoryJobHandlerRegistry();
        pool = new WorkerPool(store, registry, RetryPolicy.immediate(),
                QueueConfig.builder().workerId("interrupt-test").concurrency(2).claimBatchSize(1)
                        .build(),
                new QueueMetrics(), clock);
    }

    @AfterEach
    void tearDown() {
        pool.shutdown(PATIENCE);
    }

    /** Runs the one job through a cycle driven by hand and waits until its outcome is recorded. */
    private Job runToOutcome(String type) throws Exception {
        Job job = store.insert(new JobSubmission(type, "{}", 0, 3, null), clock.instant());
        assertThat(pool.dispatchOnce().awaitCompletion(PATIENCE)).isTrue();
        return store.find(job.id()).orElseThrow();
    }

    @Test
    @DisplayName("restoring the flag and then throwing still records the failure at once")
    void restoreThenThrowIsRecorded() throws Exception {
        registry.register("idiom", context -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while working");
        });

        Job job = runToOutcome("idiom");

        assertThat(job.state()).isEqualTo(JobState.FAILED);
        assertThat(job.lockedBy()).isNull();
        assertThat(job.lastError()).contains("interrupted while working");
    }

    @Test
    @DisplayName("swallowing the interrupt and returning still records the success")
    void swallowedInterruptStillRecordsTheSuccess() throws Exception {
        registry.register("swallow", context -> Thread.currentThread().interrupt());

        Job job = runToOutcome("swallow");

        assertThat(job.state()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    @DisplayName("restoring the flag and then failing permanently still dead-letters the job")
    void restoreThenPermanentFailureIsRecorded() throws Exception {
        registry.register("permanent", context -> {
            Thread.currentThread().interrupt();
            throw new PermanentJobFailureException("never going to work");
        });

        Job job = runToOutcome("permanent");

        assertThat(job.state()).isEqualTo(JobState.DEAD);
    }

    @Test
    @DisplayName("a handler that throws InterruptedException is recorded as a failure, as before")
    void interruptedExceptionIsRecorded() throws Exception {
        registry.register("interrupted", context -> {
            throw new InterruptedException("stopped by the shutdown deadline");
        });

        Job job = runToOutcome("interrupted");

        assertThat(job.state()).isEqualTo(JobState.FAILED);
        assertThat(job.lastError()).contains("stopped by the shutdown deadline");
    }
}
