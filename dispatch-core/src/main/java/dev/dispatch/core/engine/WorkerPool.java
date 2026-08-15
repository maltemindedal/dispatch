package dev.dispatch.core.engine;

import dev.dispatch.core.handler.JobContext;
import dev.dispatch.core.handler.JobHandler;
import dev.dispatch.core.handler.JobHandlerRegistry;
import dev.dispatch.core.handler.PermanentJobFailureException;
import dev.dispatch.core.handler.UnknownJobTypeException;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.retry.RetryPolicy;
import dev.dispatch.core.store.JobStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Claims jobs and runs them on virtual threads.
 *
 * <h2>Shape</h2>
 * One <em>dispatcher</em> thread loops: reserve capacity, claim that many jobs, hand each to the
 * executor. Each job then runs on its own virtual thread.
 *
 * <p>The split is deliberate. The dispatcher is a single long-lived platform thread — there is
 * exactly one, it lives for the life of the process, and it spends its time blocked in a database
 * call, which is precisely the workload a platform thread is still right for. Handlers are the
 * opposite: many, short-lived, and mostly waiting on I/O, which is exactly what virtual threads
 * are for. Ten thousand concurrent handlers cost ten thousand cheap stacks instead of ten thousand
 * OS threads.
 *
 * <h2>Backpressure</h2>
 * A {@link ClaimCapacity} sized to {@code concurrency} gates everything. The dispatcher reserves
 * a claim budget before it claims — blocking when the pool is saturated — capped at the claim
 * batch size, and hands unused permits straight back. So the engine never claims work it has no
 * room to run, which matters: a claimed job is invisible to every other instance until its lease
 * expires, and claiming greedily would park work on a busy node while idle nodes starve.
 *
 * <h2>Delivery semantics</h2>
 * At-least-once. A worker can finish a job and die before recording the result; the visibility
 * timeout then hands that job to someone else. Handlers must be idempotent. Recording a result is
 * always conditional on still holding the lease, so a worker that stalled past its timeout cannot
 * overwrite whoever took the job over — it counts a lost lease and moves on.
 */
public final class WorkerPool implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    /** Errors are truncated before storage; stack traces belong in logs, not in a varchar. */
    private static final int MAX_ERROR_LENGTH = 2000;

    private enum State {
        /** Built, nothing started. */
        NEW,
        /** {@link #start} spawned a dispatcher thread, which is claiming on its own. */
        RUNNING,
        /** A caller is driving cycles by hand with {@link #dispatchOnce}; no dispatcher thread. */
        DRIVEN,
        STOPPING,
        TERMINATED
    }

    private final JobStore store;
    private final JobHandlerRegistry registry;
    private final RetryPolicy retryPolicy;
    private final QueueConfig config;
    private final QueueMetrics metrics;
    private final Clock clock;
    private final ClaimCapacity capacity;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);

    private volatile ExecutorService executor;
    private volatile Thread dispatcher;
    private volatile boolean accepting;

    public WorkerPool(
            JobStore store,
            JobHandlerRegistry registry,
            RetryPolicy retryPolicy,
            QueueConfig config,
            QueueMetrics metrics,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.config = Objects.requireNonNull(config, "config");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.capacity = new ClaimCapacity(config.concurrency());
    }

    /** Starts the dispatcher. Idempotent-ish: starting twice is a programming error and throws. */
    public void start() {
        if (!state.compareAndSet(State.NEW, State.RUNNING)) {
            throw new IllegalStateException("WorkerPool already started (state=" + state.get() + ")");
        }
        beginAccepting();
        Thread thread = new Thread(this::dispatchLoop, config.workerId() + "-dispatcher");
        thread.setDaemon(true);
        dispatcher = thread;
        thread.start();
        log.info("Worker pool {} started: concurrency={}, batch={}, visibilityTimeout={}",
                config.workerId(), config.concurrency(), config.claimBatchSize(),
                config.visibilityTimeout());
    }

    private void beginAccepting() {
        accepting = true;
        executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(config.workerId() + "-job-", 0).factory());
    }

    /** True while a dispatcher thread of this pool's own is claiming. */
    public boolean isRunning() {
        return state.get() == State.RUNNING;
    }

    /** Free claim-capacity permits; equals {@code concurrency} when nothing is in flight. */
    int availablePermits() {
        return capacity.available();
    }

    /** Nudges an idle dispatcher so locally submitted work starts without waiting out the poll. */
    public void wakeUp() {
        Thread current = dispatcher;
        if (current != null) {
            LockSupport.unpark(current);
        }
    }

    // ---------------------------------------------------------------- dispatch

    private void dispatchLoop() {
        log.debug("Dispatcher {} running", Thread.currentThread().getName());
        while (accepting) {
            try {
                if (dispatchCycle().isEmpty()) {
                    // Nothing was waiting. Park until the poll interval elapses or a local
                    // submission unparks us.
                    LockSupport.parkNanos(this, config.pollInterval().toNanos());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                // Storage is unhappy. Never spin on it: back off a poll interval and retry.
                log.error("Dispatcher {} hit an unexpected error; backing off",
                        config.workerId(), e);
                LockSupport.parkNanos(this, config.pollInterval().toNanos());
            }
        }
        log.debug("Dispatcher {} stopped", Thread.currentThread().getName());
    }

    /**
     * Runs one dispatch cycle on the calling thread: reserve claim budget, claim that many jobs,
     * hand each to its own virtual thread. This is the operation {@link #start} performs in a loop,
     * exposed so a caller can drive the pool a cycle at a time instead of racing a background
     * thread — the same reason {@link QueueMaintenance#sweep()} is public.
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
        State current = state.get();
        if (current == State.NEW && state.compareAndSet(State.NEW, State.DRIVEN)) {
            beginAccepting();
        } else if (state.get() != State.DRIVEN) {
            throw new IllegalStateException(
                    "dispatchOnce() needs a pool nobody else is claiming for (state="
                    + state.get() + ")");
        }
        return dispatchCycle();
    }

    /** One claim round trip. Assumes the caller has established who owns the pool. */
    private DispatchResult dispatchCycle() throws InterruptedException {
        // Block until there is room for at least one job; this is the backpressure valve.
        int budget = capacity.reserve(config.claimBatchSize());
        if (!accepting) {
            capacity.release(budget);
            return DispatchResult.nothing(budget);
        }

        List<Job> claimed;
        try {
            claimed = store.claim(
                    config.workerId(), budget, config.visibilityTimeout(), clock.instant());
        } catch (RuntimeException e) {
            capacity.release(budget);
            throw e;
        }

        // Hand back the permits the claim did not use.
        capacity.release(budget - claimed.size());
        if (claimed.isEmpty()) {
            return DispatchResult.nothing(budget);
        }
        metrics.jobsClaimed(claimed.size());

        CountDownLatch finished = new CountDownLatch(claimed.size());
        for (Job job : claimed) {
            try {
                executor.execute(() -> {
                    try {
                        runJob(job);
                    } finally {
                        capacity.release(1);
                        finished.countDown();
                    }
                });
            } catch (RejectedExecutionException e) {
                // Shutdown raced with this claim. Leave the job RUNNING and let its visibility
                // lease expire — the sweeper on this or another instance will pick it back up.
                capacity.release(1);
                finished.countDown();
                log.warn("Job {} claimed but not dispatched (pool shutting down); "
                        + "it will be reclaimed after the visibility timeout", job.id());
            }
        }
        return new DispatchResult(claimed, budget, finished);
    }

    /**
     * What one dispatch cycle did.
     *
     * <p>{@link #dispatched()} is the batch in claim order — the order the store handed them over,
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

        /** How many permits the cycle reserved before claiming — at most the claim batch size. */
        public int claimBudget() {
            return claimBudget;
        }

        public boolean isEmpty() {
            return dispatched.isEmpty();
        }

        /**
         * Waits for every job <em>this</em> cycle dispatched to finish — handler returned and
         * outcome recorded.
         *
         * @return true if they all finished within {@code timeout}
         */
        public boolean awaitCompletion(Duration timeout) throws InterruptedException {
            return finished.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    // ---------------------------------------------------------------- execution

    private void runJob(Job job) {
        long startNanos = System.nanoTime();
        metrics.jobStarted();
        try {
            JobHandler handler = registry.require(job.type());
            handler.handle(new JobContext(job, config.workerId()));
            recordSuccess(job);
        } catch (PermanentJobFailureException e) {
            log.warn("Job {} ({}) failed permanently on attempt {}: {}",
                    job.id(), job.type(), job.attempt(), e.toString());
            metrics.attemptFailed();
            recordDeadLetter(job, describe(e));
        } catch (Throwable t) {
            metrics.attemptFailed();
            recordFailure(job, t);
            if (t instanceof InterruptedException) {
                // Shutdown passed its drain deadline and interrupted us. Restore the flag only
                // now that the failure is recorded — setting it earlier can abort the very
                // storage call that puts the job back on the queue.
                Thread.currentThread().interrupt();
            }
        } finally {
            metrics.jobFinished(Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        }
    }

    private void recordSuccess(Job job) {
        try {
            if (store.complete(job.id(), config.workerId(), clock.instant()).isPresent()) {
                metrics.jobSucceeded();
                log.debug("Job {} ({}) completed on attempt {}", job.id(), job.type(), job.attempt());
            } else {
                metrics.leaseLost();
                log.warn("Job {} finished but its lease was already gone — it ran longer than the "
                        + "visibility timeout ({}) and another worker may have re-run it",
                        job.id(), config.visibilityTimeout());
            }
        } catch (RuntimeException e) {
            // The work happened; we just could not say so. The lease expires and the job is
            // retried — which is exactly why handlers have to be idempotent.
            log.error("Job {} succeeded but the result could not be recorded", job.id(), e);
        }
    }

    private void recordFailure(Job job, Throwable failure) {
        String error = describe(failure);
        if (failure instanceof UnknownJobTypeException) {
            // Possibly a rolling deploy where another instance already has the handler, so this
            // is retryable rather than fatal — but it is worth shouting about. Submission-time
            // unknowns are refused outright by JobQueue.submit; the split is ADR-0001.
            log.error("No handler for job type '{}' on worker {}; job {} will be retried",
                    job.type(), config.workerId(), job.id());
        } else {
            log.warn("Job {} ({}) failed on attempt {}/{}: {}",
                    job.id(), job.type(), job.attempt(), job.maxRetries() + 1, error);
        }

        if (job.retriesExhausted()) {
            recordDeadLetter(job, error);
            return;
        }
        try {
            Instant now = clock.instant();
            Duration backoff = retryPolicy.backoffAfter(job.attempt());
            Instant retryAt = now.plus(backoff);
            if (store.fail(job.id(), config.workerId(), error, retryAt, now).isPresent()) {
                metrics.retryScheduled();
                log.debug("Job {} retry {} scheduled in {}", job.id(), job.attempt() + 1, backoff);
            } else {
                metrics.leaseLost();
                log.warn("Job {} failed but its lease was already gone; another worker owns it",
                        job.id());
            }
        } catch (RuntimeException e) {
            log.error("Job {} failed and the failure could not be recorded", job.id(), e);
        }
    }

    private void recordDeadLetter(Job job, String error) {
        try {
            if (store.deadLetter(job.id(), config.workerId(), error, clock.instant()).isPresent()) {
                metrics.jobDeadLettered();
                log.error("Job {} ({}) dead-lettered after {} attempt(s): {}",
                        job.id(), job.type(), job.attempt(), error);
            } else {
                metrics.leaseLost();
            }
        } catch (RuntimeException e) {
            log.error("Job {} could not be dead-lettered", job.id(), e);
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage() == null ? t.getClass().getName()
                : t.getClass().getSimpleName() + ": " + t.getMessage();
        return message.length() <= MAX_ERROR_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_LENGTH - 3) + "...";
    }

    // ---------------------------------------------------------------- shutdown

    /**
     * Graceful shutdown in three beats: stop claiming, let in-flight jobs finish, then interrupt
     * whatever is still running when the deadline passes.
     *
     * <p>Jobs interrupted at the deadline are not lost — they fail their attempt and go back on
     * the queue under the normal retry rules, and anything that never got that far is recovered by
     * its visibility lease.
     *
     * @param drainDeadline how long to wait for in-flight jobs before interrupting them
     * @return true if everything drained within the deadline
     */
    public boolean shutdown(Duration drainDeadline) {
        State previous = state.getAndSet(State.STOPPING);
        if (previous == State.NEW || previous == State.TERMINATED) {
            state.set(State.TERMINATED);
            return true;
        }
        // A DRIVEN pool has no dispatcher thread to stop, but it does have in-flight jobs to
        // drain, so it takes the same path from here.

        log.info("Worker pool {} shutting down: no longer claiming, draining {} in-flight job(s), "
                + "deadline {}", config.workerId(), metrics.inFlight(), drainDeadline);

        // 1. Stop claiming. The dispatcher may be blocked on the semaphore or parked; interrupt
        //    breaks it out of either.
        accepting = false;
        Thread thread = dispatcher;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(Duration.ofSeconds(5).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 2. Let in-flight handlers finish.
        boolean drained = false;
        ExecutorService pool = executor;
        if (pool != null) {
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
        }

        state.set(State.TERMINATED);
        log.info("Worker pool {} stopped (clean drain: {})", config.workerId(), drained);
        return drained;
    }

    @Override
    public void close() {
        shutdown(config.shutdownDrainTimeout());
    }
}
