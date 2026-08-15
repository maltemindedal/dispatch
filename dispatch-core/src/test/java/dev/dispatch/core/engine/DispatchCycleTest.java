package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dispatch.core.handler.InMemoryJobHandlerRegistry;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.retry.RetryPolicy;
import dev.dispatch.core.store.JobRows;
import dev.dispatch.core.store.JobStore;
import dev.dispatch.core.store.memory.InMemoryJobRows;
import dev.dispatch.core.testing.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
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

    @Test
    @DisplayName("concurrent first cycles either cycle or are refused by name")
    void concurrentFirstCyclesAreSafe() throws Exception {
        // The first dispatchOnce() call does one-time setup, gated by a STARTING state so a
        // second caller cannot pass the ownership guard while the executor is still null. The
        // window that guards against is a few instructions wide, and this test does not manage
        // to hit it even with the guard removed — so read it as a smoke test of the contract
        // ("a cycle, or an IllegalStateException, never anything else"), not as proof of the
        // fix. The fix is by construction; see dispatchOnce().
        registry.register("record", context -> { });
        for (int i = 0; i < 64; i++) {
            insert("record");
        }
        int callers = 16;
        CyclicBarrier go = new CyclicBarrier(callers);
        List<Future<?>> outcomes = new ArrayList<>();
        try (var threads = Executors.newFixedThreadPool(callers)) {
            for (int i = 0; i < callers; i++) {
                outcomes.add(threads.submit(() -> {
                    go.await();
                    return pool.dispatchOnce();
                }));
            }
            for (var outcome : outcomes) {
                try {
                    outcome.get(10, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    assertThat(e.getCause())
                            .as("the only acceptable refusal is a state the pool names")
                            .isInstanceOf(IllegalStateException.class);
                }
            }
        }
    }

    @Test
    @DisplayName("JobQueue.start() after dispatchOnce() leaves no sweeper running")
    void queueStartAfterDrivingLeavesNothingBehind() throws Exception {
        registry.register("record", context -> { });
        AtomicInteger sweeps = new AtomicInteger();
        // Every store operation crosses the JobRows seam exactly once, so counting scopes is
        // enough to see whether anything ran — no need to fake individual Scope methods.
        JobRows real = new InMemoryJobRows();
        JobRows counting = new JobRows() {
            @Override
            public <R> R inExclusiveScope(Function<Scope, R> work) {
                sweeps.incrementAndGet();
                return real.inExclusiveScope(work);
            }
        };
        JobQueue queue = JobQueue.builder()
                .store(JobStore.over(counting))
                .registry(registry)
                .clock(clock)
                .config(QueueConfig.builder()
                        .workerId("start-after-drive")
                        .maintenanceInterval(Duration.ofMillis(5))
                        .build())
                .build();
        try {
            queue.dispatchOnce();
            int before = sweeps.get();

            assertThatThrownBy(queue::start).isInstanceOf(IllegalStateException.class);

            // A sweeper that had been started before the throw would tick every 5ms; give it
            // ample time to prove it is not there.
            Thread.sleep(100);
            assertThat(sweeps.get())
                    .as("no maintenance sweep ran after start() was refused")
                    .isEqualTo(before);
        } finally {
            queue.shutdown(PATIENCE);
        }
    }
}
