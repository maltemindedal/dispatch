package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dispatch.core.handler.InMemoryJobHandlerRegistry;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.retry.RetryPolicy;
import dev.dispatch.core.store.JobFilter;
import dev.dispatch.core.store.JobRows;
import dev.dispatch.core.store.JobSelection;
import dev.dispatch.core.store.JobStore;
import dev.dispatch.core.store.memory.InMemoryJobRows;
import dev.dispatch.core.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A failure while recording an outcome is not the handler's failure.
 *
 * <p>The handler and the recording used to share one try block, so an {@link Error} thrown while
 * the store recorded a success, such as a driver's stack overflow, landed in the catch meant for the
 * handler. A job whose work was done was then failed and run again, or dead-lettered on its last
 * attempt.
 */
@DisplayName("An error while recording an outcome")
class RecordingErrorTest {

    private static final int CONCURRENCY = 2;

    /** In-memory rows whose first write of a COMPLETED job throws an Error, before writing it. */
    private static final class FailsToRecordASuccess implements JobRows {

        private final InMemoryJobRows delegate = new InMemoryJobRows();
        private final AtomicBoolean thrown = new AtomicBoolean();

        @Override
        public <R> R inExclusiveScope(Function<Scope, R> work) {
            return delegate.inExclusiveScope(scope -> work.apply(new Scope() {
                @Override
                public void insert(Job job) {
                    scope.insert(job);
                }

                @Override
                public Optional<Job> byId(UUID id) {
                    return scope.byId(id);
                }

                @Override
                public List<Job> matching(JobSelection selection, Instant now, int limit) {
                    return scope.matching(selection, now, limit);
                }

                @Override
                public void write(List<Job> jobs) {
                    boolean completes = jobs.stream().anyMatch(job -> job.state() == JobState.COMPLETED);
                    if (completes && thrown.compareAndSet(false, true)) {
                        throw new StackOverflowError("the store failed while recording a success");
                    }
                    scope.write(jobs);
                }

                @Override
                public void delete(UUID id) {
                    scope.delete(id);
                }

                @Override
                public List<Job> list(JobFilter filter) {
                    return scope.list(filter);
                }

                @Override
                public Optional<Job> read(UUID id) {
                    return scope.read(id);
                }

                @Override
                public Map<JobState, Long> countsByState() {
                    return scope.countsByState();
                }

                @Override
                public void deleteAll() {
                    scope.deleteAll();
                }
            }));
        }
    }

    @Test
    @DisplayName("an Error while recording a success leaves the job to its lease, not failed")
    void errorWhileRecordingASuccessIsNotAFailure() throws Exception {
        JobStore store = JobStore.over(new FailsToRecordASuccess());
        InMemoryJobHandlerRegistry registry = new InMemoryJobHandlerRegistry();
        registry.register("record", context -> { });
        QueueMetrics metrics = new QueueMetrics();
        WorkerPool pool = new WorkerPool(store, registry, RetryPolicy.immediate(),
                QueueConfig.builder().workerId("w").concurrency(CONCURRENCY).build(), metrics,
                MutableClock.atEpoch());
        try {
            Job job = store.insert(new JobSubmission("record", "{}", 0, 0, null), Instant.EPOCH);

            WorkerPool.DispatchResult cycle = pool.dispatchOnce();
            assertThat(cycle.dispatched()).extracting(Job::id).containsExactly(job.id());
            assertThat(cycle.awaitCompletion(Duration.ofSeconds(10))).isTrue();

            // The work happened and could not be recorded. That is the lease's business: it
            // expires and the job runs again, which handlers are idempotent for. Recording a
            // failure instead would have dead-lettered a job whose work was done.
            Job seen = store.find(job.id()).orElseThrow();
            assertThat(seen.state()).isEqualTo(JobState.RUNNING);
            assertThat(seen.lastError()).isNull();
            assertThat(metrics.failedAttempts()).isZero();
            assertThat(metrics.deadLettered()).isZero();
            assertThat(metrics.succeeded()).isZero();
            assertThat(pool.availablePermits()).isEqualTo(CONCURRENCY);
        } finally {
            pool.close();
        }
    }
}
