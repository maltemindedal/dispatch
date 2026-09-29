package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.dispatch.core.handler.InMemoryJobHandlerRegistry;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.retry.RetryPolicy;
import dev.dispatch.core.store.JobRows;
import dev.dispatch.core.store.JobStore;
import dev.dispatch.core.store.memory.InMemoryJobRows;
import dev.dispatch.core.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An {@link Error} escaping the store (a stack overflow in a driver, a class that fails to load,
 * an assertion) is not a {@link RuntimeException}, and the background threads used to catch only
 * that. The dispatcher thread died and the sweeper stopped silently, while the pool still reported
 * itself running; claim permits reserved for the failed cycle were never returned.
 *
 * <p>The store here fails once, then behaves.
 */
@DisplayName("Background threads survive an Error from the store")
class BackgroundErrorResilienceTest {

    private static final int CONCURRENCY = 2;

    /** Throws one Error on the first scope opened by a thread whose name ends with the suffix. */
    private static final class FailsOnce implements JobRows {

        private final InMemoryJobRows delegate = new InMemoryJobRows();
        private final String threadNameSuffix;
        final AtomicBoolean thrown = new AtomicBoolean();

        FailsOnce(String threadNameSuffix) {
            this.threadNameSuffix = threadNameSuffix;
        }

        @Override
        public <R> R inExclusiveScope(Function<Scope, R> work) {
            if (Thread.currentThread().getName().endsWith(threadNameSuffix)
                    && thrown.compareAndSet(false, true)) {
                throw new StackOverflowError("simulated Error from the storage layer");
            }
            return delegate.inExclusiveScope(work);
        }
    }

    private static QueueConfig config() {
        return QueueConfig.builder()
                .workerId("resilience")
                .concurrency(CONCURRENCY)
                .claimBatchSize(8)
                .pollInterval(Duration.ofMillis(20))
                .maintenanceInterval(Duration.ofMillis(20))
                .build();
    }

    @Test
    @DisplayName("an Error while claiming returns every permit the cycle reserved")
    void errorDuringClaimReturnsThePermits() {
        // Driven by hand on the calling thread, so the failing scope is the one the test names.
        FailsOnce rows = new FailsOnce("");
        WorkerPool pool = new WorkerPool(JobStore.over(rows), new InMemoryJobHandlerRegistry(),
                RetryPolicy.immediate(), config(), new QueueMetrics(), MutableClock.atEpoch());
        try {
            assertThatThrownBy(pool::dispatchOnce).isInstanceOf(StackOverflowError.class);

            // Nothing was claimed, so nothing may stay reserved: available + in-flight == concurrency.
            assertThat(pool.availablePermits()).isEqualTo(CONCURRENCY);
        } finally {
            pool.close();
        }
    }

    @Test
    @DisplayName("the dispatcher thread outlives an Error and goes on claiming")
    void dispatcherSurvivesAnError() {
        FailsOnce rows = new FailsOnce("-dispatcher");
        JobStore store = JobStore.over(rows);
        InMemoryJobHandlerRegistry registry = new InMemoryJobHandlerRegistry();
        registry.register("record", context -> { });
        WorkerPool pool = new WorkerPool(store, registry, RetryPolicy.immediate(), config(),
                new QueueMetrics(), MutableClock.atEpoch());
        try {
            pool.start();
            // The dispatcher's very first claim throws the Error.
            await().atMost(Duration.ofSeconds(5)).until(rows.thrown::get);

            Job job = store.insert(new JobSubmission("record", "{}", 0, 3, null), Instant.EPOCH);
            pool.wakeUp();

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(store.find(job.id()).orElseThrow().state())
                            .isEqualTo(JobState.COMPLETED));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(pool.availablePermits()).isEqualTo(CONCURRENCY));
        } finally {
            pool.close();
        }
    }

    @Test
    @DisplayName("the maintenance sweeper keeps running after an Error")
    void sweeperSurvivesAnError() {
        FailsOnce rows = new FailsOnce("-maintenance");
        JobStore store = JobStore.over(rows);
        InMemoryJobHandlerRegistry registry = new InMemoryJobHandlerRegistry();
        registry.register("record", context -> { });
        try (JobQueue queue = JobQueue.builder().store(store).registry(registry)
                .config(config()).build().start()) {
            await().atMost(Duration.ofSeconds(5)).until(rows.thrown::get);

            // Only the sweeper can promote a delayed job to PENDING.
            Job job = queue.submit(
                    JobSubmission.delayed("record", "{}", Duration.ofMillis(200), Instant.now()));

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(store.find(job.id()).orElseThrow().state())
                            .isEqualTo(JobState.COMPLETED));
        }
    }
}
