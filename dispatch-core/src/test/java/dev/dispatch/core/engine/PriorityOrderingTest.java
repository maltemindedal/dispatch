package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.dispatch.core.handler.InMemoryJobHandlerRegistry;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.store.memory.InMemoryJobStore;
import dev.dispatch.core.testing.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Priority ordering, observed through the worker pool rather than the store.
 *
 * <p>The trick that makes this deterministic: a {@link JobQueue} accepts submissions before
 * {@link JobQueue#start()} is called. So the whole backlog is loaded first, and only then does a
 * single-threaded pool start draining it — meaning execution order <em>is</em> claim order, with
 * no races to reason about.
 */
@DisplayName("Priority and fairness")
class PriorityOrderingTest {

    private MutableClock clock;
    private InMemoryJobStore store;
    private InMemoryJobHandlerRegistry registry;
    private JobQueue queue;
    private final List<String> executionOrder = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        clock = MutableClock.atEpoch();
        store = new InMemoryJobStore();
        registry = new InMemoryJobHandlerRegistry();
        registry.register("record", context -> executionOrder.add(context.payload()));
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.shutdown(Duration.ofSeconds(10));
        }
    }

    /** One job at a time, one job per claim: execution order is exactly claim order. */
    private JobQueue serialQueue() {
        return JobQueue.builder()
                .store(store)
                .registry(registry)
                .clock(clock)
                .config(QueueConfig.builder()
                        .workerId("test-worker")
                        .concurrency(1)
                        .claimBatchSize(1)
                        .pollInterval(Duration.ofMillis(20))
                        .maintenanceInterval(Duration.ofMillis(20))
                        .build())
                .build();
    }

    private Job submit(String label, int priority) {
        Job job = queue.submit(new JobSubmission("record", label, priority, 0, null));
        // Distinct creation timestamps, so the age tiebreak is well defined.
        clock.advance(Duration.ofMillis(1));
        return job;
    }

    @Test
    @DisplayName("higher priority jobs run first, whatever order they arrived in")
    void higherPriorityRunsFirst() {
        queue = serialQueue();

        submit("low", 1);
        submit("urgent", 100);
        submit("normal", 10);
        submit("also-urgent", 100);
        submit("lowest", 0);

        queue.start();

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(executionOrder).hasSize(5));

        assertThat(executionOrder)
                .containsExactly("urgent", "also-urgent", "normal", "low", "lowest");
    }

    @Test
    @DisplayName("jobs of equal priority run oldest first, so nothing starves behind its peers")
    void equalPriorityRunsOldestFirst() {
        queue = serialQueue();

        for (int i = 0; i < 8; i++) {
            submit("job-" + i, 5);
        }

        queue.start();

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(executionOrder).hasSize(8));

        assertThat(executionOrder)
                .containsExactly("job-0", "job-1", "job-2", "job-3",
                        "job-4", "job-5", "job-6", "job-7");
    }

    @Test
    @DisplayName("a job submitted later at higher priority still overtakes the queued backlog")
    void latecomerWithHigherPriorityOvertakes() {
        queue = serialQueue();

        for (int i = 0; i < 5; i++) {
            submit("bulk-" + i, 0);
        }
        submit("vip", 50);

        queue.start();

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(executionOrder).hasSize(6));

        assertThat(executionOrder.get(0)).isEqualTo("vip");
    }

    @Test
    @DisplayName("negative priorities sort below the default band")
    void negativePriorityRunsLast() {
        queue = serialQueue();

        submit("background", -10);
        submit("default", 0);
        submit("elevated", 5);

        queue.start();

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(executionOrder).hasSize(3));

        assertThat(executionOrder).containsExactly("elevated", "default", "background");
    }

    @Test
    @DisplayName("under real concurrency, high priority jobs are still claimed first")
    void priorityHoldsUnderConcurrency() {
        queue = JobQueue.builder()
                .store(store)
                .registry(registry)
                .clock(clock)
                .config(QueueConfig.builder()
                        .workerId("test-worker")
                        .concurrency(4)
                        .claimBatchSize(4)
                        .pollInterval(Duration.ofMillis(20))
                        .maintenanceInterval(Duration.ofMillis(20))
                        .build())
                .build();

        List<String> expectedFirstWave = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            submit("bulk-" + i, 0);
        }
        for (int i = 0; i < 4; i++) {
            String label = "priority-" + i;
            expectedFirstWave.add(label);
            submit(label, 99);
        }

        queue.start();

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(executionOrder).hasSize(24));

        // With four workers and a batch of four, the priority jobs form the first claimed batch.
        // Their finishing order among themselves is up to the scheduler, so compare as a set.
        assertThat(executionOrder.subList(0, 4))
                .containsExactlyInAnyOrderElementsOf(expectedFirstWave);
        assertThat(executionOrder.subList(4, 24))
                .allSatisfy(label -> assertThat(label).startsWith("bulk-"));
    }

    @Test
    @DisplayName("the store's claim ordering is the documented priority, age, id ordering")
    void claimOrderIsDocumented() {
        queue = serialQueue();
        Job first = submit("a", 5);
        Job second = submit("b", 5);
        Job third = submit("c", 9);

        List<Job> claimed = store.claim("w", 10, Duration.ofMinutes(1), clock.instant());

        assertThat(claimed).extracting(Job::id)
                .containsExactly(third.id(), first.id(), second.id());
        assertThat(claimed).isSortedAccordingTo(
                Comparator.comparingInt(Job::priority).reversed()
                        .thenComparing(Job::scheduledAt));
    }
}
