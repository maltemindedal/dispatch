package dev.dispatch.core.engine;

import dev.dispatch.core.handler.JobHandlerRegistry;
import dev.dispatch.core.handler.UnknownJobTypeException;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobActionResult;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.retry.ExponentialBackoffRetryPolicy;
import dev.dispatch.core.retry.RetryPolicy;
import dev.dispatch.core.store.JobFilter;
import dev.dispatch.core.store.JobStore;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The engine's front door: a store, a handler registry, a worker pool and a maintenance sweeper,
 * wired together.
 *
 * <p>Deliberately plain Java. Nothing here knows what Spring is, and swapping the in-memory rows
 * for {@code JdbcJobRows} is a one-line change at the call site.
 *
 * <p>Typical use:
 * <pre>{@code
 * var registry = new InMemoryJobHandlerRegistry().register("email", new EmailJobHandler());
 * try (var queue = JobQueue.builder()
 *         .store(JobStore.inMemory())
 *         .registry(registry)
 *         .build()) {
 *     queue.start();
 *     queue.submit(JobSubmission.of("email", "{\"to\":\"a@b.c\"}"));
 * }   // close() drains in-flight work before returning
 * }</pre>
 */
public final class JobQueue implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JobQueue.class);

    private final JobStore store;
    private final JobHandlerRegistry registry;
    private final QueueConfig config;
    private final QueueMetrics metrics;
    private final Clock clock;
    private final WorkerPool workers;
    private final QueueMaintenance maintenance;

    private JobQueue(Builder builder) {
        this.store = Objects.requireNonNull(builder.store, "store");
        this.registry = Objects.requireNonNull(builder.registry, "registry");
        this.config = builder.config == null ? QueueConfig.defaults() : builder.config;
        this.clock = builder.clock == null ? Clock.systemUTC() : builder.clock;
        this.metrics = new QueueMetrics();
        RetryPolicy retryPolicy = builder.retryPolicy == null
                ? ExponentialBackoffRetryPolicy.defaults()
                : builder.retryPolicy;
        this.workers = new WorkerPool(store, registry, retryPolicy, config, metrics, clock);
        this.maintenance = new QueueMaintenance(store, config, metrics, clock, workers::wakeUp);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts the worker pool and the sweeper. Until this is called, nothing executes.
     *
     * <p>The pool goes first because it is the one that can refuse — if {@link #dispatchOnce()}
     * already owns it, this throws, and a sweeper started a line earlier would have kept running
     * behind that exception.
     *
     * @throws IllegalStateException if the pool was already started, or is being driven by hand
     */
    public JobQueue start() {
        workers.start();
        maintenance.start();
        log.info("Job queue {} started with handlers for {}", config.workerId(),
                registry.registeredTypes());
        return this;
    }

    // ---------------------------------------------------------------- producing

    /**
     * Enqueues a job. Returns immediately with the persisted snapshot — PENDING if it is due now,
     * SCHEDULED if it was submitted with a future {@code scheduledAt}.
     *
     * @throws UnknownJobTypeException if no handler is registered here for the type — a typo
     *         should fail at the door, not retry its way to the dead-letter state. A handler
     *         missing at <em>execution</em> time stays retryable instead (rolling deploys); the
     *         split is recorded in ADR-0001.
     */
    public Job submit(JobSubmission submission) {
        registry.require(submission.type());
        Job job = store.insert(submission, clock.instant());
        metrics.jobSubmitted();
        // Skip the poll interval for work produced on this instance.
        workers.wakeUp();
        log.debug("Submitted job {} ({}) as {}", job.id(), job.type(), job.state());
        return job;
    }

    /** Convenience for the common case: run this type with this payload, as soon as possible. */
    public Job submit(String type, String payload) {
        return submit(JobSubmission.of(type, payload));
    }

    /** Convenience for a delayed job. */
    public Job submitDelayed(String type, String payload, Duration delay) {
        return submit(JobSubmission.delayed(type, payload, delay, clock.instant()));
    }

    // ---------------------------------------------------------------- querying

    public Optional<Job> find(UUID id) {
        return store.find(id);
    }

    public List<Job> list(JobFilter filter) {
        return store.list(filter);
    }

    /**
     * Cancels a job that has not started yet — once a worker holds the lease there is nothing
     * safe to cancel from out here. A refusal says why, with the state the store observed in the
     * same atomic step.
     */
    public JobActionResult cancel(UUID id) {
        JobActionResult result = store.cancel(id);
        if (result instanceof JobActionResult.Done) {
            log.debug("Cancelled job {}", id);
        }
        return result;
    }

    /**
     * Moves a DEAD job back to PENDING with a fresh retry budget. A refusal says why, with the
     * state the store observed in the same atomic step.
     */
    public JobActionResult retryDeadJob(UUID id) {
        JobActionResult result = store.requeueDeadJob(id, clock.instant());
        if (result instanceof JobActionResult.Done done) {
            log.info("Job {} revived from the dead-letter state", done.job().id());
            workers.wakeUp();
        }
        return result;
    }

    public QueueStats stats() {
        return new QueueStats(config.workerId(), store.countsByState(), metrics);
    }

    public QueueMetrics metrics() {
        return metrics;
    }

    public QueueConfig config() {
        return config;
    }

    public boolean isRunning() {
        return workers.isRunning();
    }

    /**
     * Runs one maintenance sweep synchronously — promote due jobs, reclaim expired leases.
     * The scheduler does this on a timer; tests call it directly to avoid sleeping.
     */
    public QueueMaintenance.SweepResult sweep() {
        return maintenance.sweep();
    }

    /**
     * Runs one dispatch cycle synchronously — claim a batch and hand it to workers — and reports
     * what it claimed. {@link #start()} does this on a thread of its own; a caller that wants to
     * watch the queue move one batch at a time does it here instead, and never has to reason about
     * a background dispatcher's timing.
     *
     * <p>Use this <em>or</em> {@link #start()}, not both. The result's
     * {@link WorkerPool.DispatchResult#awaitCompletion} waits for this batch's handlers to finish.
     *
     * @throws IllegalStateException if {@link #start()} already owns the worker pool
     * @throws InterruptedException if interrupted while waiting for claim capacity
     */
    public WorkerPool.DispatchResult dispatchOnce() throws InterruptedException {
        return workers.dispatchOnce();
    }

    // ---------------------------------------------------------------- shutdown

    /**
     * Stops claiming, drains in-flight jobs within {@code drainDeadline}, then interrupts anything
     * still running.
     *
     * @return true if every in-flight job finished within the deadline
     */
    public boolean shutdown(Duration drainDeadline) {
        maintenance.close();
        boolean drained = workers.shutdown(drainDeadline);
        try {
            store.close();
        } catch (Exception e) {
            log.warn("Job store did not close cleanly", e);
        }
        return drained;
    }

    @Override
    public void close() {
        shutdown(config.shutdownDrainTimeout());
    }

    /** Assembles a {@link JobQueue}; only {@code store} and {@code registry} are required. */
    public static final class Builder {
        private JobStore store;
        private JobHandlerRegistry registry;
        private RetryPolicy retryPolicy;
        private QueueConfig config;
        private Clock clock;

        public Builder store(JobStore value) {
            this.store = value;
            return this;
        }

        public Builder registry(JobHandlerRegistry value) {
            this.registry = value;
            return this;
        }

        /** Defaults to {@link ExponentialBackoffRetryPolicy#defaults()}. */
        public Builder retryPolicy(RetryPolicy value) {
            this.retryPolicy = value;
            return this;
        }

        /** Defaults to {@link QueueConfig#defaults()}. */
        public Builder config(QueueConfig value) {
            this.config = value;
            return this;
        }

        /** Defaults to {@link Clock#systemUTC()}; tests pass a controllable clock. */
        public Builder clock(Clock value) {
            this.clock = value;
            return this;
        }

        public JobQueue build() {
            return new JobQueue(this);
        }
    }
}
