package dev.dispatch.core.job;

import static dev.dispatch.core.job.JobState.COMPLETED;
import static dev.dispatch.core.job.JobState.DEAD;
import static dev.dispatch.core.job.JobState.FAILED;
import static dev.dispatch.core.job.JobState.PENDING;
import static dev.dispatch.core.job.JobState.RUNNING;
import static dev.dispatch.core.job.JobState.SCHEDULED;

import java.util.EnumSet;
import java.util.Set;

/**
 * Every move the engine makes, named. This enum is the whole transition table: nothing else is
 * legal. Each constant is used by exactly one {@link Job} method, so add a constant together with
 * the method that makes the move.
 *
 * <p>The only way into DEAD is from RUNNING, since only an attempt can exhaust the retry budget or
 * fail permanently. A job that has not started is removed by cancelling it, which deletes the row
 * rather than moving it to any state. COMPLETED is terminal: a re-run is a new job.
 */
public enum Transition {
    /** A worker takes a visibility lease. */
    CLAIM(EnumSet.of(PENDING), RUNNING),
    /** The handler succeeded. */
    COMPLETE(EnumSet.of(RUNNING), COMPLETED),
    /** The handler failed with a retry left; the job waits out its backoff. */
    FAIL(EnumSet.of(RUNNING), FAILED),
    /** Retries exhausted, a permanent failure, or the lease lapsed on the last attempt. */
    DEAD_LETTER(EnumSet.of(RUNNING), DEAD),
    /** The lease lapsed with a retry left (sweeper). */
    LEASE_EXPIRED(EnumSet.of(RUNNING), PENDING),
    /** The delay or the backoff elapsed (sweeper). */
    PROMOTE(EnumSet.of(SCHEDULED, FAILED), PENDING),
    /** An operator's manual retry, via POST /jobs/{id}/retry. */
    MANUAL_RETRY(EnumSet.of(DEAD), PENDING);

    private final Set<JobState> from;
    private final JobState to;

    Transition(Set<JobState> from, JobState to) {
        this.from = from;
        this.to = to;
    }

    public Set<JobState> from() {
        return from;
    }

    public JobState to() {
        return to;
    }

    /**
     * @return {@link #to()} when {@code current} is one of {@link #from()}
     * @throws IllegalJobTransitionException otherwise
     */
    public JobState applyTo(JobState current) {
        if (!from.contains(current)) {
            throw new IllegalJobTransitionException(this, current);
        }
        return to;
    }
}
