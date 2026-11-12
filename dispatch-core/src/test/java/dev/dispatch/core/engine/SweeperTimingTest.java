package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.dispatch.core.store.JobRows;
import dev.dispatch.core.store.JobStore;
import dev.dispatch.core.store.memory.InMemoryJobRows;
import dev.dispatch.core.testing.MutableClock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * When the sweeper thread sweeps and what {@link QueueMaintenance#close} waits for. No document
 * promises either, so these tests are the record: a change to one of them is a decision, not a
 * side effect of how the thread is built.
 */
@DisplayName("Sweeper timing")
class SweeperTimingTest {

    private static final String WORKER_ID = "sweeper-timing";

    /** Counts the scopes the sweeper opens, and holds each one open until {@code release}. */
    private static final class CountingRows implements JobRows {

        private final InMemoryJobRows delegate = new InMemoryJobRows();
        final AtomicInteger scopes = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release;

        CountingRows(boolean blocking) {
            release = new CountDownLatch(blocking ? 1 : 0);
        }

        @Override
        public <R> R inExclusiveScope(Function<Scope, R> work) {
            scopes.incrementAndGet();
            entered.countDown();
            awaitReleaseIgnoringInterrupts();
            return delegate.inExclusiveScope(work);
        }

        /** A store call that an interrupt does not end, as a blocked socket read does not. */
        private void awaitReleaseIgnoringInterrupts() {
            boolean interrupted = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static QueueMaintenance maintenance(CountingRows rows, Duration interval) {
        return new QueueMaintenance(JobStore.over(rows),
                QueueConfig.builder().workerId(WORKER_ID).maintenanceInterval(interval).build(),
                new QueueMetrics(), MutableClock.atEpoch(), () -> { });
    }

    private static Thread.State sweeperThreadState() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().equals(WORKER_ID + "-maintenance"))
                .map(Thread::getState)
                .findFirst()
                .orElse(Thread.State.NEW);
    }

    @Test
    @DisplayName("the first sweep waits one maintenance interval after start")
    void firstSweepWaitsOneInterval() {
        CountingRows rows = new CountingRows(false);
        QueueMaintenance maintenance = maintenance(rows, Duration.ofHours(1));
        try {
            maintenance.start();

            await().atMost(Duration.ofSeconds(5))
                    .until(() -> sweeperThreadState() == Thread.State.TIMED_WAITING);
            assertThat(rows.scopes).hasValue(0);
        } finally {
            maintenance.close();
        }
    }

    @Test
    @DisplayName("close returns without waiting for a sweep that is still in the store")
    void closeDoesNotWaitForASweepInFlight() throws InterruptedException {
        CountingRows rows = new CountingRows(true);
        QueueMaintenance maintenance = maintenance(rows, Duration.ofMillis(1));
        try {
            maintenance.start();
            assertThat(rows.entered.await(5, TimeUnit.SECONDS)).isTrue();

            long before = System.nanoTime();
            maintenance.close();

            assertThat(Duration.ofNanos(System.nanoTime() - before))
                    .isLessThan(Duration.ofSeconds(2));
        } finally {
            rows.release.countDown();
            maintenance.close();
        }
    }
}
