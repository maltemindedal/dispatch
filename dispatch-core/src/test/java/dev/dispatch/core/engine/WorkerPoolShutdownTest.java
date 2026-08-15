package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dispatch.core.handler.InMemoryJobHandlerRegistry;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.retry.RetryPolicy;
import dev.dispatch.core.store.JobStore;
import dev.dispatch.core.testing.MutableClock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Permit conservation across the pool's shutdown paths. Shutdown races the dispatcher's claim
 * loop — the not-accepting early return, jobs finishing mid-drain, stragglers interrupted at the
 * deadline — and whatever combination fires, every permit must come home: a leak here would
 * silently shrink a restarted pool's capacity.
 */
@DisplayName("Worker pool shutdown conservation")
class WorkerPoolShutdownTest {

    private static final int CONCURRENCY = 4;

    private MutableClock clock;
    private JobStore store;
    private InMemoryJobHandlerRegistry registry;
    private WorkerPool pool;

    @BeforeEach
    void setUp() {
        clock = MutableClock.atEpoch();
        store = JobStore.inMemory();
        registry = new InMemoryJobHandlerRegistry();
        pool = new WorkerPool(store, registry, RetryPolicy.immediate(),
                QueueConfig.builder()
                        .workerId("shutdown-test")
                        .concurrency(CONCURRENCY)
                        .claimBatchSize(2)
                        .pollInterval(Duration.ofMillis(20))
                        .build(),
                new QueueMetrics(), clock);
    }

    @Test
    @DisplayName("a clean drain returns every permit")
    void cleanDrainConservesPermits() throws Exception {
        CountDownLatch allStarted = new CountDownLatch(CONCURRENCY);
        CountDownLatch releaseHandlers = new CountDownLatch(1);
        registry.register("block", context -> {
            allStarted.countDown();
            releaseHandlers.await();
        });
        for (int i = 0; i < CONCURRENCY; i++) {
            store.insert(new JobSubmission("block", "{}", 0, 3, null), clock.instant());
        }

        pool.start();
        assertThat(allStarted.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(pool.availablePermits()).as("saturated pool").isZero();

        releaseHandlers.countDown();
        boolean drained = pool.shutdown(Duration.ofSeconds(10));

        assertThat(drained).isTrue();
        assertThat(pool.availablePermits())
                .as("every reserved permit is returned across the shutdown race")
                .isEqualTo(CONCURRENCY);
    }

    @Test
    @DisplayName("stragglers interrupted at the drain deadline still return their permits")
    void interruptedStragglersConservePermits() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        registry.register("block", context -> {
            started.countDown();
            new CountDownLatch(1).await();
        });
        store.insert(new JobSubmission("block", "{}", 0, 3, null), clock.instant());

        pool.start();
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        boolean drained = pool.shutdown(Duration.ofMillis(100));

        assertThat(drained).as("the blocked job cannot finish in time").isFalse();
        assertThat(pool.availablePermits())
                .as("the interrupted job's permit is released on its way out")
                .isEqualTo(CONCURRENCY);
    }
}
