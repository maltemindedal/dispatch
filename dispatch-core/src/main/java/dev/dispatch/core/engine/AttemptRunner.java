package dev.dispatch.core.engine;

import dev.dispatch.core.handler.JobContext;
import dev.dispatch.core.handler.JobHandler;
import dev.dispatch.core.handler.JobHandlerRegistry;
import dev.dispatch.core.handler.PermanentJobFailureException;
import dev.dispatch.core.handler.UnknownJobTypeException;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.retry.RetryPolicy;
import dev.dispatch.core.store.JobStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one attempt of a claimed job and records how it ended.
 *
 * <p>{@link WorkerPool} claims jobs and hands each to its own virtual thread, which calls
 * {@link #run}. This class looks up the handler, runs it, and records the outcome under the
 * job's lease: COMPLETED on success, FAILED or DEAD on a failure as the store decides from the
 * retry budget, and DEAD at once on a {@link PermanentJobFailureException}. It counts the
 * attempt in {@link QueueMetrics} as it goes. Claim capacity stays with the pool, whose task
 * returns the job's permit once {@code run} returns.
 *
 * <h2>Delivery semantics</h2>
 * At-least-once. A worker can finish a job and die before recording the result; the visibility
 * timeout then hands that job to someone else. Handlers must be idempotent. Recording a result is
 * always conditional on still holding the lease, so a worker that stalled past its timeout cannot
 * overwrite whoever took the job over. It counts a lost lease and moves on.
 */
final class AttemptRunner {

    private static final Logger log = LoggerFactory.getLogger(AttemptRunner.class);

    /** Errors are truncated before storage; stack traces belong in logs, not in a varchar. */
    private static final int MAX_ERROR_LENGTH = 2000;

    private final JobStore store;
    private final JobHandlerRegistry registry;
    private final RetryPolicy retryPolicy;
    private final QueueConfig config;
    private final QueueMetrics metrics;
    private final Clock clock;

    AttemptRunner(
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
    }

    /**
     * Runs the handler for {@code job}, a snapshot a claim returned, and records the outcome under
     * its lease. If the store fails while recording, the job stays RUNNING until its lease expires.
     * A RuntimeException from the store is logged. An Error is not caught here: it ends this
     * thread once the interrupt flag and the metrics are put right.
     */
    void run(Job job) {
        long startNanos = System.nanoTime();
        metrics.jobStarted();
        // Whether the handler left this thread interrupted, or was interrupted out of its work. The
        // flag is cleared while the outcome is recorded and restored afterwards: JDBC calls made on
        // an interrupted thread fail (a pool's wait for a connection throws, a socket read is
        // closed), so recording with the flag set would lose the outcome and leave the job RUNNING
        // until its lease expires. Handlers that restore the flag before throwing, or swallow it and
        // return, are common enough that the engine cannot assume otherwise.
        boolean interrupted = false;
        try {
            Throwable failure = runHandler(job);
            interrupted = Thread.interrupted() || failure instanceof InterruptedException;
            // Recorded outside the handler's catch, so a store that fails while recording, with an
            // Error included, is never taken for the handler failing.
            if (failure == null) {
                recordSuccess(job);
            } else if (failure instanceof PermanentJobFailureException) {
                // The capped description, as stored: a handler's message can be arbitrarily long.
                String error = describe(failure);
                log.warn("Job {} ({}) failed permanently on attempt {}: {}",
                        job.id(), job.type(), job.attempt(), error);
                metrics.attemptFailed();
                recordDeadLetter(job, error);
            } else {
                metrics.attemptFailed();
                recordFailure(job, failure);
            }
        } finally {
            if (interrupted) {
                // Shutdown passed its drain deadline and interrupted us, or the handler did. The
                // interrupt stays a fact about this thread, so put it back, but only now that the
                // outcome is recorded: with it set earlier the recording is what fails.
                Thread.currentThread().interrupt();
            }
            metrics.jobFinished(Duration.ofNanos(System.nanoTime() - startNanos));
        }
    }

    /**
     * Looks up the handler and runs it. A missing handler counts as the handler failing.
     *
     * @return what the handler threw, or null if it returned normally
     */
    private Throwable runHandler(Job job) {
        try {
            JobHandler handler = registry.require(job.type());
            handler.handle(new JobContext(job, config.workerId()));
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private void recordSuccess(Job job) {
        try {
            if (store.complete(job.lease(), clock.instant()).isPresent()) {
                metrics.jobSucceeded();
                log.debug("Job {} ({}) completed on attempt {}", job.id(), job.type(), job.attempt());
            } else {
                metrics.leaseLost();
                log.warn("Job {} finished attempt {} but its lease was already gone. It ran longer "
                        + "than the visibility timeout ({}) and another attempt may have re-run it",
                        job.id(), job.attempt(), config.visibilityTimeout());
            }
        } catch (RuntimeException e) {
            // The work happened; we just could not say so. The lease expires and the job is
            // retried. That is why handlers have to be idempotent.
            log.error("Job {} succeeded but the result could not be recorded", job.id(), e);
        }
    }

    private void recordFailure(Job job, Throwable failure) {
        String error = describe(failure);
        if (failure instanceof UnknownJobTypeException) {
            // Possibly a rolling deploy where another instance already has the handler, so this
            // is retryable rather than fatal, but it is worth shouting about. Submission-time
            // unknowns are refused outright by JobQueue.submit; the split is ADR-0001.
            log.error("No handler for job type '{}' on worker {}; job {} will be retried",
                    job.type(), config.workerId(), job.id());
        } else {
            log.warn("Job {} ({}) failed on attempt {}/{}: {}",
                    job.id(), job.type(), job.attempt(), job.maxRetries() + 1, error);
        }

        try {
            Instant now = clock.instant();
            // The store decides whether a retry is left, on the row it holds, and asks the policy
            // for a backoff only when one is.
            Optional<Job> recorded = store.fail(job.lease(), error, retryPolicy, now);
            if (recorded.isEmpty()) {
                metrics.leaseLost();
                log.warn("Job {} failed on attempt {} but its lease was already gone; the failure "
                        + "was not recorded", job.id(), job.attempt());
            } else if (recorded.get().state() == JobState.DEAD) {
                deadLettered(job, error);
            } else {
                metrics.retryScheduled();
                log.debug("Job {} retry {} scheduled in {}", job.id(), job.attempt() + 1,
                        Duration.between(now, recorded.get().scheduledAt()));
            }
        } catch (RuntimeException e) {
            log.error("Job {} failed and the failure could not be recorded", job.id(), e);
        }
    }

    private void recordDeadLetter(Job job, String error) {
        try {
            if (store.deadLetter(job.lease(), error, clock.instant()).isPresent()) {
                deadLettered(job, error);
            } else {
                metrics.leaseLost();
            }
        } catch (RuntimeException e) {
            log.error("Job {} could not be dead-lettered", job.id(), e);
        }
    }

    /** Counts and logs a dead letter the store has recorded, however the attempt got there. */
    private void deadLettered(Job job, String error) {
        metrics.jobDeadLettered();
        log.error("Job {} ({}) dead-lettered after {} attempt(s): {}",
                job.id(), job.type(), job.attempt(), error);
    }

    private static String describe(Throwable t) {
        String message = t.getMessage() == null ? t.getClass().getName()
                : t.getClass().getSimpleName() + ": " + t.getMessage();
        return message.length() <= MAX_ERROR_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_LENGTH - 3) + "...";
    }
}
