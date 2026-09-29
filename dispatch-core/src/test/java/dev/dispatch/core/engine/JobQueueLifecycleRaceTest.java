package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.dispatch.core.handler.InMemoryJobHandlerRegistry;
import dev.dispatch.core.store.JobStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code start()} racing {@code shutdown()} must not leave a thread running.
 *
 * <p>{@code start()} used to bring up the worker pool and then the sweeper as two separate steps.
 * A {@code shutdown()} landing between them closed a sweeper that did not exist yet, and the sweeper
 * was then created after the queue was closed: it ran for ever against a closed store. Spring hits
 * this when a startup failure closes the context while the queue bean is still being built.
 */
@DisplayName("Job queue start/shutdown race")
class JobQueueLifecycleRaceTest {

    /**
     * How many times to race them. Without the fix roughly one round in a hundred strands a
     * sweeper, so this catches a regression essentially every time; with it, no round can.
     */
    private static final int ROUNDS = 1500;

    private static final String PREFIX = "lifecycle-race-";

    @Test
    @DisplayName("no sweeper or dispatcher outlives a shutdown that raced the start")
    void noThreadOutlivesTheRace() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            JobQueue queue = JobQueue.builder()
                    .store(JobStore.inMemory())
                    .registry(new InMemoryJobHandlerRegistry())
                    .config(QueueConfig.builder().workerId(PREFIX + round).concurrency(1).build())
                    .build();
            CyclicBarrier go = new CyclicBarrier(2);
            Thread starter = new Thread(() -> {
                try {
                    go.await();
                    queue.start();
                } catch (IllegalStateException lostTheRace) {
                    // shutdown got in first; starting a shut-down queue is refused
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread stopper = new Thread(() -> {
                try {
                    go.await();
                    queue.shutdown(Duration.ofSeconds(5));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            starter.start();
            stopper.start();
            starter.join();
            stopper.join();
        }

        // Threads that are merely finishing (shutdown interrupts the sweeper without waiting for
        // it) are gone within moments; a stranded one never leaves.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(liveQueueThreads()).as("threads still running after every shutdown")
                        .isEmpty());
    }

    @Test
    @DisplayName("a queue that has been shut down refuses to start")
    void shutDownQueueCannotStart() {
        JobQueue queue = JobQueue.builder()
                .store(JobStore.inMemory())
                .registry(new InMemoryJobHandlerRegistry())
                .config(QueueConfig.builder().workerId(PREFIX + "sequential").build())
                .build();
        queue.shutdown(Duration.ofSeconds(1));

        assertThatThrownBy(queue::start).isInstanceOf(IllegalStateException.class);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(liveQueueThreads()).isEmpty());
    }

    private static List<String> liveQueueThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith(PREFIX))
                .toList();
    }
}
