package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dispatch.core.handler.InMemoryJobHandlerRegistry;
import dev.dispatch.core.retry.RetryPolicy;
import dev.dispatch.core.store.JobStore;
import dev.dispatch.core.testing.MutableClock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The configured concurrency is actually reachable — the docs promise that {@code concurrency}
 * caps jobs in flight, and this pins the cap from below as well as above.
 */
@DisplayName("Worker pool concurrency")
class WorkerPoolConcurrencyTest {

    @Test
    @DisplayName("concurrency=16 with claimBatchSize=8 runs 16 jobs at once, not 8")
    void fullConcurrencyIsReachable() throws Exception {
        // Regression for the permit leak: the old inline accounting converged on
        // claimBatchSize permits, so only 8 of these 16 blocking jobs would ever start.
        JobStore store = JobStore.inMemory();
        InMemoryJobHandlerRegistry registry = new InMemoryJobHandlerRegistry();
        CountDownLatch allStarted = new CountDownLatch(16);
        CountDownLatch releaseHandlers = new CountDownLatch(1);
        registry.register("block", context -> {
            allStarted.countDown();
            releaseHandlers.await();
        });

        try (JobQueue queue = JobQueue.builder()
                .store(store)
                .registry(registry)
                .clock(MutableClock.atEpoch())
                .retryPolicy(RetryPolicy.immediate())
                .config(QueueConfig.builder()
                        .workerId("concurrency-test")
                        .concurrency(16)
                        .claimBatchSize(8)
                        .pollInterval(Duration.ofMillis(20))
                        .maintenanceInterval(Duration.ofMillis(20))
                        .shutdownDrainTimeout(Duration.ofSeconds(10))
                        .build())
                .build()
                .start()) {
            for (int i = 0; i < 16; i++) {
                queue.submit("block", "{}");
            }

            boolean reachedFullConcurrency = allStarted.await(10, TimeUnit.SECONDS);
            assertThat(reachedFullConcurrency)
                    .as("all 16 jobs should run concurrently under concurrency=16")
                    .isTrue();
            assertThat(queue.metrics().inFlight())
                    .as("jobs in flight once the pool is saturated")
                    .isEqualTo(16);

            releaseHandlers.countDown();
        }
    }
}
