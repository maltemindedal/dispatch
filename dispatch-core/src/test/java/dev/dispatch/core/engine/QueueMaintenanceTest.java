package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobActionResult;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.store.JobFilter;
import dev.dispatch.core.store.JobStore;
import dev.dispatch.core.store.JobStoreException;
import dev.dispatch.core.store.memory.InMemoryJobStore;
import dev.dispatch.core.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sweeper's survival promise, previously stated only in a comment: a storage error during one
 * sweep must never kill the scheduled task, because nothing would recover it.
 */
@DisplayName("Queue maintenance")
class QueueMaintenanceTest {

    /** Delegates to an in-memory store; {@code failing} makes every sweep-facing call throw. */
    private static final class FlakyStore implements JobStore {
        final InMemoryJobStore delegate = new InMemoryJobStore();
        final AtomicBoolean failing = new AtomicBoolean(false);
        final AtomicInteger failuresSeen = new AtomicInteger();

        private void maybeFail() {
            if (failing.get()) {
                failuresSeen.incrementAndGet();
                throw new JobStoreException("storage is unwell");
            }
        }

        @Override
        public int promoteDueJobs(Instant now, int limit) {
            maybeFail();
            return delegate.promoteDueJobs(now, limit);
        }

        @Override
        public int reclaimExpiredLeases(Instant now, int limit) {
            maybeFail();
            return delegate.reclaimExpiredLeases(now, limit);
        }

        @Override
        public Job insert(JobSubmission submission, Instant now) {
            return delegate.insert(submission, now);
        }

        @Override
        public Optional<Job> find(UUID id) {
            return delegate.find(id);
        }

        @Override
        public List<Job> list(JobFilter filter) {
            return delegate.list(filter);
        }

        @Override
        public List<Job> claim(String workerId, int limit, Duration lease, Instant now) {
            return delegate.claim(workerId, limit, lease, now);
        }

        @Override
        public Optional<Job> complete(UUID id, String workerId, Instant now) {
            return delegate.complete(id, workerId, now);
        }

        @Override
        public Optional<Job> fail(UUID id, String workerId, String error, Instant retryAt,
                Instant now) {
            return delegate.fail(id, workerId, error, retryAt, now);
        }

        @Override
        public Optional<Job> deadLetter(UUID id, String workerId, String error, Instant now) {
            return delegate.deadLetter(id, workerId, error, now);
        }

        @Override
        public JobActionResult cancel(UUID id) {
            return delegate.cancel(id);
        }

        @Override
        public JobActionResult requeueDeadJob(UUID id, Instant now) {
            return delegate.requeueDeadJob(id, now);
        }

        @Override
        public Map<JobState, Long> countsByState() {
            return delegate.countsByState();
        }

        @Override
        public void deleteAll() {
            delegate.deleteAll();
        }
    }

    private MutableClock clock;
    private FlakyStore store;
    private QueueMaintenance maintenance;
    private final AtomicInteger wakeUps = new AtomicInteger();

    @BeforeEach
    void setUp() {
        clock = MutableClock.atEpoch();
        store = new FlakyStore();
        maintenance = new QueueMaintenance(store,
                QueueConfig.builder()
                        .workerId("maintenance-test")
                        .maintenanceInterval(Duration.ofMillis(20))
                        .build(),
                new QueueMetrics(), clock, wakeUps::incrementAndGet);
    }

    @AfterEach
    void tearDown() {
        maintenance.close();
    }

    @Test
    @DisplayName("a failing sweep does not kill the scheduled task")
    void sweepSurvivesStorageFailures() {
        Job delayed = store.insert(new JobSubmission("record", "{}", 0, 3,
                clock.instant().plus(Duration.ofMinutes(1))), clock.instant());

        store.failing.set(true);
        maintenance.start();

        // Let the scheduler hit the failure repeatedly; each throw would have silently ended a
        // scheduleWithFixedDelay task that let it escape.
        await().atMost(Duration.ofSeconds(5)).until(() -> store.failuresSeen.get() >= 3);

        // Storage recovers and the job comes due: the still-alive sweeper must promote it.
        store.failing.set(false);
        clock.advance(Duration.ofMinutes(2));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(store.find(delayed.id()).orElseThrow().state())
                        .isEqualTo(JobState.PENDING));
    }

    @Test
    @DisplayName("a sweep that promotes work wakes the dispatcher; an idle sweep does not")
    void wakeUpOnlyWhenWorkAppeared() {
        maintenance.sweep();
        assertThat(wakeUps).hasValue(0);

        store.insert(new JobSubmission("record", "{}", 0, 3,
                clock.instant().plus(Duration.ofSeconds(1))), clock.instant());
        clock.advance(Duration.ofSeconds(2));
        QueueMaintenance.SweepResult result = maintenance.sweep();

        assertThat(result.promoted()).isEqualTo(1);
        assertThat(wakeUps).hasValue(1);
    }

    @Test
    @DisplayName("starting twice is a programming error and throws")
    void doubleStartThrows() {
        maintenance.start();
        assertThatThrownBy(maintenance::start).isInstanceOf(IllegalStateException.class);
    }
}
