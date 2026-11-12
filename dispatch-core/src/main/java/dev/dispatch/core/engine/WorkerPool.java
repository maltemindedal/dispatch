package dev.dispatch.core.engine;

import dev.dispatch.core.handler.JobHandlerRegistry;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.retry.RetryPolicy;
import dev.dispatch.core.store.JobStore;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Claims jobs and runs them on virtual threads.
 *
 * <h2>Shape</h2>
 * One <em>dispatcher</em> thread loops: reserve capacity, claim that many jobs, hand each to the
 * executor. Each job then runs on its own virtual thread, where an {@link AttemptRunner} runs its
 * handler and records the outcome under the job's lease. Delivery is at-least-once, as
 * {@link AttemptRunner} explains.
 *
 * <p>The split is deliberate. The dispatcher is a single long-lived platform thread. There is
 * exactly one, it lives for the life of the process, and it spends its time blocked in a database
 * call, which is precisely the workload a platform thread is still right for. Handlers are the
 * opposite: many, short-lived, and mostly waiting on I/O, which is exactly what virtual threads
 * are for. Ten thousand concurrent handlers cost ten thousand cheap stacks instead of ten thousand
 * OS threads.
 *
 * <h2>Backpressure</h2>
 * A {@link ClaimCapacity} sized to {@code concurrency} gates everything. The dispatcher reserves
 * a claim budget before it claims. It blocks when the pool is saturated and caps the budget at the
 * batch size, and hands unused permits straight back. So the engine never claims work it has no
 * room to run, which matters: a claimed job is invisible to every other instance until its lease
 * expires, and claiming greedily would park work on a busy node while idle nodes starve.
 */
public final class WorkerPool implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    private enum State {
        /** Built, nothing started. */
        NEW,
        /** {@link #start} spawned a dispatcher thread, which is claiming on its own. */
        RUNNING,
        /** A caller is driving cycles by hand with {@link #dispatchOnce}; no dispatcher thread. */
        DRIVEN,
        STOPPING,
        TERMINATED;

        boolean mayClaim() {
            return this == RUNNING || this == DRIVEN;
        }
    }

    private final JobStore store;
    private final QueueConfig config;
    private final QueueMetrics metrics;
    private final Clock clock;
    private final ClaimCapacity capacity;
    private final AttemptRunner runner;

    /**
     * Makes each lifecycle move one step: the state and the executor and dispatcher it implies
     * change together. Without it a {@code shutdown()} landing inside {@code start()} found
     * nothing to stop, and the dispatcher started after the pool was shut down. Nothing waits
     * while holding it, and the dispatcher thread never takes it.
     */
    private final ReentrantLock lifecycle = new ReentrantLock();

    /** Written under {@link #lifecycle}, and only forward: a pool never leaves TERMINATED. */
    private volatile State state = State.NEW;
    /** Set in the step that leaves NEW for RUNNING or DRIVEN. */
    private volatile ExecutorService executor;
    /** Set, and started, in the step that leaves NEW for RUNNING. */
    private volatile BackgroundLoop dispatcher;

    public WorkerPool(
            JobStore store,
            JobHandlerRegistry registry,
            RetryPolicy retryPolicy,
            QueueConfig config,
            QueueMetrics metrics,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.config = Objects.requireNonNull(config, "config");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.capacity = new ClaimCapacity(config.concurrency());
        this.runner = new AttemptRunner(store, registry, retryPolicy, config, metrics, clock);
    }

    /** Starts the dispatcher. Idempotent-ish: starting twice is a programming error and throws. */
    public void start() {
        lifecycle.lock();
        try {
            if (state != State.NEW) {
                throw new IllegalStateException("WorkerPool already started (state=" + state + ")");
            }
            executor = newExecutor();
            dispatcher = new BackgroundLoop(config.workerId() + "-dispatcher",
                    Duration.ZERO, config.pollInterval(), () -> !dispatchCycle().isEmpty());
            // Before the loop starts, or its first cycle would see NEW and claim nothing.
            state = State.RUNNING;
            dispatcher.start();
        } finally {
            lifecycle.unlock();
        }
        log.info("Worker pool {} started: concurrency={}, batch={}, visibilityTimeout={}",
                config.workerId(), config.concurrency(), config.claimBatchSize(),
                config.visibilityTimeout());
    }

    private ExecutorService newExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(config.workerId() + "-job-", 0).factory());
    }

    /** True while a dispatcher thread of this pool's own is claiming. */
    public boolean isRunning() {
        return state == State.RUNNING;
    }

    /** Free claim-capacity permits; equals {@code concurrency} when nothing is in flight. */
    int availablePermits() {
        return capacity.available();
    }

    /** Nudges an idle dispatcher so locally submitted work starts without waiting out the poll. */
    public void wakeUp() {
        BackgroundLoop current = dispatcher;
        if (current != null) {
            current.wake();
        }
    }

    // ---------------------------------------------------------------- dispatch

    /**
     * Runs one dispatch cycle on the calling thread: reserve claim budget, claim that many jobs,
     * hand each to its own virtual thread. This is the operation {@link #start} performs in a loop,
     * exposed so a caller can drive the pool a cycle at a time instead of racing a background
     * thread. That is why {@link QueueMaintenance#sweep()} is public too.
     *
     * <p>It returns as soon as the claimed jobs are <em>dispatched</em>, not when they are done;
     * that is what the dispatcher loop needs, since waiting for each batch would serialise a pool
     * sized for concurrency. To wait for this cycle's jobs specifically, call
     * {@link DispatchResult#awaitCompletion}.
     *
     * <p>Driving cycles by hand and running the dispatcher are mutually exclusive: whichever you do
     * first claims the pool, and the other throws. Two claimers would fight over the same claim
     * budget and neither would tell you anything reliable.
     *
     * @throws IllegalStateException if {@link #start} owns this pool, or it is shutting down
     * @throws InterruptedException if interrupted while waiting for claim capacity
     */
    public DispatchResult dispatchOnce() throws InterruptedException {
        lifecycle.lock();
        try {
            if (state == State.NEW) {
                executor = newExecutor();
                state = State.DRIVEN;
            }
            if (state != State.DRIVEN) {
                throw new IllegalStateException(
                        "dispatchOnce() needs a pool nobody else is claiming for (state="
                        + state + ")");
            }
        } finally {
            lifecycle.unlock();
        }
        return dispatchCycle();
    }

    /** One claim round trip. Assumes the caller has established who owns the pool. */
    private DispatchResult dispatchCycle() throws InterruptedException {
        // Block until there is room for at least one job; this is the backpressure valve.
        int budget = capacity.reserve(config.claimBatchSize());
        // The permits this cycle holds that no running job owns yet. Whatever is still owed when
        // the cycle ends, however it ends, goes back in the finally block; a job that has been
        // handed to the executor owns its permit from then on and returns it itself.
        int owed = budget;
        try {
            if (!state.mayClaim()) {
                return DispatchResult.nothing(budget);
            }

            List<Job> claimed = store.claim(
                    config.workerId(), budget, config.visibilityTimeout(), clock.instant());

            // Hand back the permits the claim did not use.
            capacity.release(budget - claimed.size());
            owed = claimed.size();
            if (claimed.isEmpty()) {
                return DispatchResult.nothing(budget);
            }
            metrics.jobsClaimed(claimed.size());

            CountDownLatch finished = new CountDownLatch(claimed.size());
            for (Job job : claimed) {
                try {
                    executor.execute(() -> {
                        try {
                            runner.run(job);
                        } finally {
                            capacity.release(1);
                            finished.countDown();
                        }
                    });
                    owed--;
                } catch (RejectedExecutionException e) {
                    // Shutdown raced with this claim. Leave the job RUNNING and let its visibility
                    // lease expire. The sweeper on this or another instance will pick it back up.
                    capacity.release(1);
                    owed--;
                    finished.countDown();
                    log.warn("Job {} claimed but not dispatched (pool shutting down); "
                            + "it will be reclaimed after the visibility timeout", job.id());
                }
            }
            return new DispatchResult(claimed, budget, finished);
        } finally {
            capacity.release(owed);
        }
    }

    /**
     * What one dispatch cycle did.
     *
     * <p>{@link #dispatched()} is the batch in claim order, the order the store handed them over,
     * which is the thing worth asserting about priority and fairness. The virtual-thread scheduler
     * decides what order they actually <em>start</em> in, and never promised otherwise.
     */
    public static final class DispatchResult {

        private final List<Job> dispatched;
        private final int claimBudget;
        private final CountDownLatch finished;

        private DispatchResult(List<Job> dispatched, int claimBudget, CountDownLatch finished) {
            this.dispatched = List.copyOf(dispatched);
            this.claimBudget = claimBudget;
            this.finished = finished;
        }

        private static DispatchResult nothing(int claimBudget) {
            return new DispatchResult(List.of(), claimBudget, new CountDownLatch(0));
        }

        /** The jobs this cycle claimed and handed to workers, in claim order. */
        public List<Job> dispatched() {
            return dispatched;
        }

        /** How many permits the cycle reserved before claiming. It is at most the claim batch size. */
        public int claimBudget() {
            return claimBudget;
        }

        public boolean isEmpty() {
            return dispatched.isEmpty();
        }

        /**
         * Waits for every job <em>this</em> cycle dispatched to finish. The handler has returned and
         * outcome recorded.
         *
         * @return true if they all finished within {@code timeout}
         */
        public boolean awaitCompletion(Duration timeout) throws InterruptedException {
            return finished.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    // ---------------------------------------------------------------- shutdown

    /**
     * Graceful shutdown in three beats: stop claiming, let in-flight jobs finish, then interrupt
     * whatever is still running when the deadline passes.
     *
     * <p>Jobs interrupted at the deadline are not lost. They fail their attempt and go back on
     * the queue under the normal retry rules, and anything that never got that far is recovered by
     * its visibility lease.
     *
     * @param drainDeadline how long to wait for in-flight jobs before interrupting them
     * @return true if everything drained within the deadline
     */
    public boolean shutdown(Duration drainDeadline) {
        BackgroundLoop loop;
        ExecutorService pool;
        lifecycle.lock();
        try {
            if (state == State.NEW || state == State.TERMINATED) {
                state = State.TERMINATED;
                return true;
            }
            // A second shutdown() during the first one's drain finds STOPPING and waits on the
            // same executor.
            state = State.STOPPING;
            loop = dispatcher;
            pool = executor;
        } finally {
            lifecycle.unlock();
        }

        log.info("Worker pool {} shutting down: no longer claiming, draining {} in-flight job(s), "
                + "deadline {}", config.workerId(), metrics.inFlight(), drainDeadline);

        // 1. Stop claiming. The dispatcher may be blocked on the semaphore or parked; interrupt
        //    breaks it out of either. A DRIVEN pool has no dispatcher, only jobs to drain.
        if (loop != null) {
            loop.stop();
            loop.awaitStopped(Duration.ofSeconds(5));
        }

        // 2. Let in-flight handlers finish.
        boolean drained = false;
        pool.shutdown();
        try {
            drained = pool.awaitTermination(drainDeadline.toMillis(), TimeUnit.MILLISECONDS);
            if (!drained) {
                // 3. Deadline passed: interrupt the stragglers.
                log.warn("Worker pool {} did not drain within {}; interrupting {} job(s)",
                        config.workerId(), drainDeadline, metrics.inFlight());
                pool.shutdownNow();
                pool.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        lifecycle.lock();
        try {
            state = State.TERMINATED;
        } finally {
            lifecycle.unlock();
        }
        log.info("Worker pool {} stopped (clean drain: {})", config.workerId(), drained);
        return drained;
    }

    @Override
    public void close() {
        shutdown(config.shutdownDrainTimeout());
    }
}
