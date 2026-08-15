package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.dispatch.core.handler.InMemoryJobHandlerRegistry;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.store.JobStore;
import dev.dispatch.core.testing.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Crash recovery via the visibility timeout.
 *
 * <p>A crashed worker is simulated the honest way: claim a job straight from the store under some
 * other worker's name and then never report a result. The row sits in RUNNING with a lease nobody
 * will ever release — exactly the state a {@code kill -9} leaves behind — and the live queue's
 * sweeper has to notice and recover it.
 */
@DisplayName("Visibility timeout and crash recovery")
class VisibilityTimeoutTest {

    private static final Duration VISIBILITY_TIMEOUT = Duration.ofSeconds(30);

    private MutableClock clock;
    private JobStore store;
    private InMemoryJobHandlerRegistry registry;
    private JobQueue queue;
    private final List<String> executed = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        clock = MutableClock.atEpoch();
        store = JobStore.inMemory();
        registry = new InMemoryJobHandlerRegistry();
        registry.register("record", context -> executed.add(context.payload()));
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.shutdown(Duration.ofSeconds(10));
        }
    }

    private JobQueue startQueue() {
        queue = JobQueue.builder()
                .store(store)
                .registry(registry)
                .clock(clock)
                .config(QueueConfig.builder()
                        .workerId("live-worker")
                        .concurrency(2)
                        .pollInterval(Duration.ofMillis(20))
                        .maintenanceInterval(Duration.ofMillis(20))
                        .visibilityTimeout(VISIBILITY_TIMEOUT)
                        .build())
                .build()
                .start();
        return queue;
    }

    @Test
    @DisplayName("a job orphaned by a crashed worker is picked up again once its lease expires")
    void orphanedJobIsRecovered() {
        Job job = store.insert(new JobSubmission("record", "orphan", 0, 3, null), clock.instant());
        // A worker claims the job and is then killed: no completion, no failure, just silence.
        store.claim("crashed-worker", 1, VISIBILITY_TIMEOUT, clock.instant());
        assertThat(store.find(job.id()).orElseThrow().state()).isEqualTo(JobState.RUNNING);

        startQueue();

        // While the lease is live, nobody touches it — even though the owner is long gone.
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(executed).isEmpty();
            assertThat(store.find(job.id()).orElseThrow().lockedBy()).isEqualTo("crashed-worker");
        });

        // Once it expires, the sweeper reclaims the job and a live worker runs it.
        clock.advance(VISIBILITY_TIMEOUT.plusSeconds(1));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(executed).containsExactly("orphan");
            assertThat(store.find(job.id()).orElseThrow().state()).isEqualTo(JobState.COMPLETED);
        });
        assertThat(queue.metrics().leasesReclaimed()).isEqualTo(1);
    }

    @Test
    @DisplayName("the reclaimed attempt still counts against the retry budget")
    void reclaimedAttemptCountsAgainstBudget() {
        Job job = store.insert(new JobSubmission("record", "orphan", 0, 3, null), clock.instant());
        store.claim("crashed-worker", 1, VISIBILITY_TIMEOUT, clock.instant());

        startQueue();
        clock.advance(VISIBILITY_TIMEOUT.plusSeconds(1));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(store.find(job.id()).orElseThrow().state()).isEqualTo(JobState.COMPLETED));

        // Attempt 1 was the crashed worker's; attempt 2 was the recovery. A crash loop must not
        // be able to retry forever.
        assertThat(store.find(job.id()).orElseThrow().attempt()).isEqualTo(2);
    }

    @Test
    @DisplayName("a job whose lease has not expired is never stolen")
    void liveLeaseIsRespected() {
        Job job = store.insert(new JobSubmission("record", "busy", 0, 3, null), clock.instant());
        store.claim("other-worker", 1, VISIBILITY_TIMEOUT, clock.instant());

        startQueue();
        clock.advance(VISIBILITY_TIMEOUT.minusSeconds(1));

        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(queue.sweep().reclaimed()).isZero();
            assertThat(executed).isEmpty();
            assertThat(store.find(job.id()).orElseThrow().lockedBy()).isEqualTo("other-worker");
        });
    }

    @Test
    @DisplayName("a worker that comes back after losing its lease cannot record a result")
    void zombieWorkerIsIgnored() {
        Job job = store.insert(new JobSubmission("record", "zombie", 0, 3, null), clock.instant());
        store.claim("stalled-worker", 1, VISIBILITY_TIMEOUT, clock.instant());

        startQueue();
        clock.advance(VISIBILITY_TIMEOUT.plusSeconds(1));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(store.find(job.id()).orElseThrow().state()).isEqualTo(JobState.COMPLETED));

        // The stalled worker finally finishes and tries to report. Too late: it lost the lease,
        // and letting it write now would clobber the result that actually happened.
        assertThat(store.complete(job.id(), "stalled-worker", clock.instant())).isEmpty();
        assertThat(store.find(job.id()).orElseThrow().state()).isEqualTo(JobState.COMPLETED);
    }

    @Test
    @DisplayName("the sweeper reclaims a whole batch of orphans at once")
    void reclaimsManyOrphansAtOnce() {
        for (int i = 0; i < 10; i++) {
            store.insert(new JobSubmission("record", "orphan-" + i, 0, 3, null), clock.instant());
        }
        store.claim("crashed-worker", 10, VISIBILITY_TIMEOUT, clock.instant());

        startQueue();
        clock.advance(VISIBILITY_TIMEOUT.plusSeconds(1));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(executed).hasSize(10);
            assertThat(queue.stats().depth(JobState.COMPLETED)).isEqualTo(10);
        });
        assertThat(queue.metrics().leasesReclaimed()).isEqualTo(10);
    }
}
