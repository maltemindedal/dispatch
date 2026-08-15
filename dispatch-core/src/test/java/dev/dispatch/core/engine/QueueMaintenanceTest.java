package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.store.JobFilter;
import dev.dispatch.core.store.JobRows;
import dev.dispatch.core.store.JobSelection;
import dev.dispatch.core.store.JobStore;
import dev.dispatch.core.store.JobStoreException;
import dev.dispatch.core.store.memory.InMemoryJobRows;
import dev.dispatch.core.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
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

    /**
     * In-memory rows that can be told to break. {@code failing} makes every selection throw, which
     * is what a sweep does first — and faking at the {@link JobRows} seam rather than at
     * {@link JobStore} means one overridden method instead of thirteen delegating ones.
     */
    private static final class FlakyRows implements JobRows {
        final JobRows delegate = new InMemoryJobRows();
        final AtomicBoolean failing = new AtomicBoolean(false);
        final AtomicInteger failuresSeen = new AtomicInteger();

        @Override
        public <R> R inExclusiveScope(Function<Scope, R> work) {
            return delegate.inExclusiveScope(scope -> work.apply(new Scope() {

                @Override
                public List<Job> matching(JobSelection selection, Instant now, int limit) {
                    if (failing.get()) {
                        failuresSeen.incrementAndGet();
                        throw new JobStoreException("storage is unwell");
                    }
                    return scope.matching(selection, now, limit);
                }

                @Override
                public void insert(Job job) {
                    scope.insert(job);
                }

                @Override
                public Optional<Job> byId(UUID id) {
                    return scope.byId(id);
                }

                @Override
                public Optional<Job> read(UUID id) {
                    return scope.read(id);
                }

                @Override
                public void write(List<Job> jobs) {
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

    private MutableClock clock;
    private FlakyRows rows;
    private JobStore store;
    private QueueMaintenance maintenance;
    private final AtomicInteger wakeUps = new AtomicInteger();

    @BeforeEach
    void setUp() {
        clock = MutableClock.atEpoch();
        rows = new FlakyRows();
        store = JobStore.over(rows);
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

        rows.failing.set(true);
        maintenance.start();

        // Let the scheduler hit the failure repeatedly; each throw would have silently ended a
        // scheduleWithFixedDelay task that let it escape.
        await().atMost(Duration.ofSeconds(5)).until(() -> rows.failuresSeen.get() >= 3);

        // Storage recovers and the job comes due: the still-alive sweeper must promote it.
        rows.failing.set(false);
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
