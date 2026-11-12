package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.dispatch.core.handler.InMemoryJobHandlerRegistry;
import dev.dispatch.core.retry.RetryPolicy;
import dev.dispatch.core.store.JobStore;
import dev.dispatch.core.testing.MutableClock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code WorkerPool.start()} racing {@code WorkerPool.shutdown()} must not leave a dispatcher
 * running. {@link JobQueueLifecycleRaceTest} covers the queue, which serialises the two calls
 * itself. This covers a pool used directly.
 */
@DisplayName("Worker pool start/shutdown race")
class WorkerPoolLifecycleRaceTest {

    /**
     * How many times to race them. Before the fix, 1,000 rounds stranded 42 to 157 dispatchers,
     * and in about 540 more {@code shutdown()} threw on a dispatcher that {@code start()} had
     * published but not yet started. So this caught it every time. With the fix, no round can
     * do either.
     */
    private static final int ROUNDS = 1000;

    private static final String PREFIX = "pool-lifecycle-race-";

    @Test
    @DisplayName("no dispatcher outlives a shutdown that landed while the pool was starting")
    void noDispatcherOutlivesTheRace() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            WorkerPool pool = new WorkerPool(JobStore.inMemory(), new InMemoryJobHandlerRegistry(),
                    RetryPolicy.immediate(),
                    QueueConfig.builder().workerId(PREFIX + round).concurrency(1).build(),
                    new QueueMetrics(), MutableClock.atEpoch());
            Thread starter = new Thread(pool::start);
            starter.start();
            // isRunning() turns true partway through start(), which puts the shutdown inside it.
            while (!pool.isRunning() && starter.isAlive()) {
                Thread.onSpinWait();
            }
            pool.shutdown(Duration.ofSeconds(5));
            starter.join();

            assertThat(pool.isRunning()).as("pool running after shutdown, round %d", round)
                    .isFalse();
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(liveDispatchers()).as("dispatchers still running after every shutdown")
                        .isEmpty());
    }

    private static List<String> liveDispatchers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith(PREFIX))
                .toList();
    }
}
