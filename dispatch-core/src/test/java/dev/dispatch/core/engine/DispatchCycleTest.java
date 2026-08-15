package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dispatch.core.handler.InMemoryJobHandlerRegistry;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.retry.RetryPolicy;
import dev.dispatch.core.store.JobStore;
import dev.dispatch.core.testing.MutableClock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The dispatch cycle as an operation in its own right.
 *
 * <p>The worker pool used to expose only its lifecycle — start, shut down, wake up — so the only
 * way to observe a claim was to start a thread and watch the store until something changed. These
 * pin the operation that replaced that: what one cycle claims, what it reports, and the rule that
 * two claimers must not share a pool.
 */
@DisplayName("Dispatch cycle")
class DispatchCycleTest {

    private static final int CONCURRENCY = 4;
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private MutableClock clock;
    private JobStore store;
    private InMemoryJobHandlerRegistry registry;
    private WorkerPool pool;

    @BeforeEach
    void setUp() {
        clock = MutableClock.atEpoch();
        store = JobStore.inMemory();
        registry = new InMemoryJobHandlerRegistry();
        pool = new WorkerPool(store, registry, RetryPolicy.immediate(),
                QueueConfig.builder()
                        .workerId("cycle-test")
                        .concurrency(CONCURRENCY)
                        .claimBatchSize(2)
                        .build(),
                new QueueMetrics(), clock);
    }

    @AfterEach
    void tearDown() {
        pool.shutdown(PATIENCE);
    }

    private Job insert(String type) {
        return store.insert(new JobSubmission(type, "{}", 0, 3, null), clock.instant());
    }

    @Test
    @DisplayName("an empty queue yields an empty cycle and gives every permit back")
    void emptyQueueDispatchesNothing() throws Exception {
        WorkerPool.DispatchResult result = pool.dispatchOnce();

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.dispatched()).isEmpty();
        assertThat(result.claimBudget()).isEqualTo(2);
        assertThat(pool.availablePermits()).isEqualTo(CONCURRENCY);
    }

    @Test
    @DisplayName("a cycle claims at most the batch size and reports what it took")
    void oneCycleClaimsOneBatch() throws Exception {
        registry.register("record", context -> { });
        for (int i = 0; i < 5; i++) {
            insert("record");
        }

        WorkerPool.DispatchResult result = pool.dispatchOnce();

        assertThat(result.claimBudget()).isEqualTo(2);
        assertThat(result.dispatched()).hasSize(2);
        assertThat(result.dispatched()).allSatisfy(job ->
                assertThat(job.state()).isEqualTo(JobState.RUNNING));
    }

    @Test
    @DisplayName("awaiting a cycle waits for that batch's outcomes, not for a poll interval")
    void awaitCompletionSeesRecordedOutcomes() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        registry.register("record", context -> runs.incrementAndGet());
        Job job = insert("record");

        WorkerPool.DispatchResult result = pool.dispatchOnce();

        assertThat(result.awaitCompletion(PATIENCE)).isTrue();
        // No polling: once the cycle is complete the outcome is already in the store.
        assertThat(runs).hasValue(1);
        assertThat(store.find(job.id()).orElseThrow().state()).isEqualTo(JobState.COMPLETED);
        assertThat(pool.availablePermits()).isEqualTo(CONCURRENCY);
    }

    @Test
    @DisplayName("a started pool refuses hand-driven cycles")
    void startedPoolRefusesDispatchOnce() {
        pool.start();

        assertThatThrownBy(pool::dispatchOnce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RUNNING");
    }

    @Test
    @DisplayName("a hand-driven pool refuses a dispatcher thread")
    void drivenPoolRefusesStart() throws Exception {
        pool.dispatchOnce();

        assertThatThrownBy(pool::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DRIVEN");
    }
}
