package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.dispatch.core.handler.InMemoryJobHandlerRegistry;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobActionResult;
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

@DisplayName("Delayed and scheduled jobs")
class ScheduledJobTest {

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

        queue = JobQueue.builder()
                .store(store)
                .registry(registry)
                .clock(clock)
                .config(QueueConfig.builder()
                        .workerId("test-worker")
                        .concurrency(2)
                        .pollInterval(Duration.ofMillis(20))
                        .maintenanceInterval(Duration.ofMillis(20))
                        .build())
                .build()
                .start();
    }

    @AfterEach
    void tearDown() {
        queue.shutdown(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a delayed job waits in SCHEDULED and does not run early")
    void delayedJobWaits() {
        Job job = queue.submitDelayed("record", "later", Duration.ofHours(1));

        assertThat(job.state()).isEqualTo(JobState.SCHEDULED);
        assertThat(job.scheduledAt()).isEqualTo(clock.instant().plus(Duration.ofHours(1)));

        // Give the dispatcher and the sweeper several real cycles to misbehave in.
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(executed).isEmpty();
            assertThat(store.find(job.id()).orElseThrow().state()).isEqualTo(JobState.SCHEDULED);
        });
    }

    @Test
    @DisplayName("a delayed job runs once its time arrives")
    void delayedJobRunsWhenDue() {
        Job job = queue.submitDelayed("record", "later", Duration.ofHours(1));

        clock.advance(Duration.ofHours(1));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(executed).containsExactly("later");
            assertThat(store.find(job.id()).orElseThrow().state()).isEqualTo(JobState.COMPLETED);
        });
    }

    @Test
    @DisplayName("a job scheduled for the past is claimable immediately")
    void pastScheduleRunsNow() {
        Job job = queue.submit(new JobSubmission(
                "record", "overdue", 0, 3, clock.instant().minus(Duration.ofDays(1))));

        assertThat(job.state()).isEqualTo(JobState.PENDING);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(executed).containsExactly("overdue"));
    }

    @Test
    @DisplayName("several delayed jobs come due in schedule order, not submission order")
    void jobsBecomeDueInScheduleOrder() {
        queue.submit(new JobSubmission("record", "third", 0, 3,
                clock.instant().plus(Duration.ofMinutes(30))));
        queue.submit(new JobSubmission("record", "first", 0, 3,
                clock.instant().plus(Duration.ofMinutes(10))));
        queue.submit(new JobSubmission("record", "second", 0, 3,
                clock.instant().plus(Duration.ofMinutes(20))));

        clock.advance(Duration.ofMinutes(10));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(executed).containsExactly("first"));

        clock.advance(Duration.ofMinutes(10));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(executed).containsExactly("first", "second"));

        clock.advance(Duration.ofMinutes(10));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(executed).containsExactly("first", "second", "third"));
    }

    @Test
    @DisplayName("a scheduled job can be cancelled before it comes due")
    void scheduledJobCanBeCancelled() {
        Job job = queue.submitDelayed("record", "never", Duration.ofHours(1));

        assertThat(queue.cancel(job.id())).isInstanceOf(JobActionResult.Done.class);

        clock.advance(Duration.ofHours(2));
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(executed).isEmpty());
        assertThat(queue.find(job.id())).isEmpty();
    }

    @Test
    @DisplayName("scheduled jobs show up in the queue depth as SCHEDULED, not PENDING")
    void scheduledJobsCountSeparately() {
        queue.submitDelayed("record", "a", Duration.ofHours(1));
        queue.submitDelayed("record", "b", Duration.ofHours(2));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            QueueStats stats = queue.stats();
            assertThat(stats.depth(JobState.SCHEDULED)).isEqualTo(2);
            assertThat(stats.depth(JobState.PENDING)).isZero();
            assertThat(stats.backlog()).isEqualTo(2);
        });
    }
}
