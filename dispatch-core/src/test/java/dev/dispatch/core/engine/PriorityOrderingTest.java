package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dispatch.core.handler.InMemoryJobHandlerRegistry;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.store.JobStore;
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
 * <p>Nothing here starts a dispatcher. The queue is driven a cycle at a time with
 * {@link JobQueue#dispatchOnce()}, so every assertion is about a value the engine returned rather
 * than about how fast a background thread got somewhere. There is no polling, no rendezvous latch
 * and no timing to tune — the concurrent case in particular used to need all three.
 */
@DisplayName("Priority and fairness")
class PriorityOrderingTest {

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private MutableClock clock;
    private JobStore store;
    private InMemoryJobHandlerRegistry registry;
    private JobQueue queue;
    private final List<String> executionOrder = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        clock = MutableClock.atEpoch();
        store = JobStore.inMemory();
        registry = new InMemoryJobHandlerRegistry();
        registry.register("record", context -> executionOrder.add(context.payload()));
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.shutdown(PATIENCE);
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
                        .build())
                .build();
    }

    private Job submit(String label, int priority) {
        Job job = queue.submit(new JobSubmission("record", label, priority, 0, null));
        // Distinct creation timestamps, so the age tiebreak is well defined.
        clock.advance(Duration.ofMillis(1));
        return job;
    }

    /** Runs {@code count} cycles, each one finishing before the next begins. */
    private void runCycles(int count) throws InterruptedException {
        for (int i = 0; i < count; i++) {
            assertThat(queue.dispatchOnce().awaitCompletion(PATIENCE))
                    .as("cycle %d finished", i)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("higher priority jobs run first, whatever order they arrived in")
    void higherPriorityRunsFirst() throws Exception {
        queue = serialQueue();

        submit("low", 1);
        submit("urgent", 100);
        submit("normal", 10);
        submit("also-urgent", 100);
        submit("lowest", 0);

        runCycles(5);

        assertThat(executionOrder)
                .containsExactly("urgent", "also-urgent", "normal", "low", "lowest");
    }

    @Test
    @DisplayName("jobs of equal priority run oldest first, so nothing starves behind its peers")
    void equalPriorityRunsOldestFirst() throws Exception {
        queue = serialQueue();

        for (int i = 0; i < 8; i++) {
            submit("job-" + i, 5);
        }

        runCycles(8);

        assertThat(executionOrder)
                .containsExactly("job-0", "job-1", "job-2", "job-3",
                        "job-4", "job-5", "job-6", "job-7");
    }

    @Test
    @DisplayName("a job submitted later at higher priority still overtakes the queued backlog")
    void latecomerWithHigherPriorityOvertakes() throws Exception {
        queue = serialQueue();

        for (int i = 0; i < 5; i++) {
            submit("bulk-" + i, 0);
        }
        submit("vip", 50);

        runCycles(6);

        assertThat(executionOrder.get(0)).isEqualTo("vip");
    }

    @Test
    @DisplayName("negative priorities sort below the default band")
    void negativePriorityRunsLast() throws Exception {
        queue = serialQueue();

        submit("background", -10);
        submit("default", 0);
        submit("elevated", 5);

        runCycles(3);

        assertThat(executionOrder).containsExactly("elevated", "default", "background");
    }

    @Test
    @DisplayName("under real concurrency, high priority jobs are still claimed first")
    void priorityHoldsUnderConcurrency() throws Exception {
        int concurrency = 4;
        queue = JobQueue.builder()
                .store(store)
                .registry(registry)
                .clock(clock)
                .config(QueueConfig.builder()
                        .workerId("test-worker")
                        .concurrency(concurrency)
                        .claimBatchSize(concurrency)
                        .build())
                .build();

        for (int i = 0; i < 20; i++) {
            submit("bulk-" + i, 0);
        }
        List<String> expectedFirstWave = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            String label = "priority-" + i;
            expectedFirstWave.add(label);
            submit(label, 99);
        }

        // The subject is claim order, so assert on what the cycle claimed. Execution order cannot
        // answer this: once handlers run in parallel, the virtual-thread scheduler decides who
        // starts first and has never promised to follow dispatch order.
        WorkerPool.DispatchResult firstWave = queue.dispatchOnce();

        assertThat(firstWave.claimBudget()).isEqualTo(concurrency);
        assertThat(firstWave.dispatched()).extracting(Job::payload)
                .containsExactlyElementsOf(expectedFirstWave);

        assertThat(firstWave.awaitCompletion(PATIENCE)).isTrue();
        assertThat(queue.dispatchOnce().dispatched()).extracting(Job::payload)
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
