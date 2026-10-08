package dev.dispatch.core.job;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable snapshot of a job row.
 *
 * <p>Every lifecycle change produces a new {@code Job} through one of the transition methods
 * below, and every one of those validates against {@link JobState#requireTransitionTo}. Stores
 * persist the result; they never mutate state by hand.
 *
 * @param id          stable identity, assigned at submission
 * @param type        routing key into the {@link dev.dispatch.core.handler.JobHandlerRegistry}
 * @param payload     opaque JSON document handed to the handler; the engine never parses it
 * @param priority    higher runs first; ties broken by {@code scheduledAt} then {@code createdAt}
 * @param maxRetries  retries allowed <em>beyond</em> the first attempt (so 2 => up to 3 attempts)
 * @param attempt     attempts started so far; incremented at claim time, 0 before the first claim
 * @param state       current lifecycle state
 * @param scheduledAt earliest instant this job may be claimed; also carries the retry backoff
 * @param createdAt   submission time
 * @param updatedAt   time of the most recent transition
 * @param lockedUntil visibility deadline while RUNNING; null in every other state
 * @param lockedBy    id of the worker holding the lease; null in every other state
 * @param leaseId     id of the claim holding the lease, new for every claim; null in every other
 *                    state
 * @param lastError   summary of the most recent failure; null if never failed
 */
public record Job(
        UUID id,
        String type,
        String payload,
        int priority,
        int maxRetries,
        int attempt,
        JobState state,
        Instant scheduledAt,
        Instant createdAt,
        Instant updatedAt,
        Instant lockedUntil,
        String lockedBy,
        UUID leaseId,
        String lastError) {

    public Job {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(scheduledAt, "scheduledAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative: " + maxRetries);
        }
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must not be negative: " + attempt);
        }
    }

    /** Builds the initial snapshot for a submission: PENDING if due now, SCHEDULED if delayed. */
    public static Job newJob(UUID id, JobSubmission submission, Instant now) {
        Instant scheduledAt = submission.scheduledAt() == null ? now : submission.scheduledAt();
        JobState initial = scheduledAt.isAfter(now) ? JobState.SCHEDULED : JobState.PENDING;
        return new Job(
                id,
                submission.type(),
                submission.payload(),
                submission.priority(),
                submission.maxRetries(),
                0,
                initial,
                scheduledAt,
                now,
                now,
                null,
                null,
                null,
                null);
    }

    /** Retries still available after the attempts started so far. Never negative. */
    public int retriesRemaining() {
        int used = Math.max(0, attempt - 1);
        return Math.max(0, maxRetries - used);
    }

    /** True once the current attempt has burned the whole retry budget. */
    public boolean retriesExhausted() {
        return retriesRemaining() == 0;
    }

    /** True when the visibility lease has lapsed and another worker may reclaim this job. */
    public boolean leaseExpiredAt(Instant now) {
        return state == JobState.RUNNING && lockedUntil != null && !lockedUntil.isAfter(now);
    }

    /** True when a delayed or backing-off job has come due. */
    public boolean dueAt(Instant now) {
        return !scheduledAt.isAfter(now);
    }

    /**
     * The lease this job is held under. Every job a claim returns is RUNNING, so the worker records
     * the attempt's outcome with {@code claimed.lease()}.
     *
     * @throws IllegalStateException in every state but RUNNING, where nobody holds a lease
     */
    public Lease lease() {
        if (state != JobState.RUNNING) {
            throw new IllegalStateException("Job " + id + " is " + state + " and holds no lease");
        }
        return new Lease(id, lockedBy, attempt, leaseId);
    }

    /**
     * True while this job is held under {@code lease}: RUNNING, under the lease's claim, locked by
     * the lease's worker, as the lease's attempt. Stores check this before recording any result. A
     * worker that stalled past its visibility timeout must not overwrite whoever took the job over,
     * and neither may an earlier claim of the same worker, even one with the same attempt number.
     *
     * <p>The lease id alone would tell claims apart. The worker and attempt checks stay for
     * rolling deploys: an instance that predates lease ids claims without writing one, so a row it
     * holds can still carry the lease id of an earlier claim.
     */
    public boolean heldUnder(Lease lease) {
        return state == JobState.RUNNING
                && id.equals(lease.jobId())
                && lease.leaseId().equals(leaseId)
                && lease.workerId().equals(lockedBy)
                && attempt == lease.attempt();
    }

    /**
     * PENDING -> RUNNING: takes a visibility lease and counts the attempt.
     *
     * @param leaseId the claim's own id, new for every claim, so no two claims share a lease
     */
    public Job claimedBy(String workerId, UUID leaseId, Instant now, Duration visibilityTimeout) {
        state.requireTransitionTo(JobState.RUNNING);
        return new Job(id, type, payload, priority, maxRetries, attempt + 1, JobState.RUNNING,
                scheduledAt, createdAt, now, now.plus(visibilityTimeout),
                Objects.requireNonNull(workerId, "workerId"),
                Objects.requireNonNull(leaseId, "leaseId"), lastError);
    }

    /** RUNNING -> COMPLETED: releases the lease. */
    public Job completed(Instant now) {
        state.requireTransitionTo(JobState.COMPLETED);
        return new Job(id, type, payload, priority, maxRetries, attempt, JobState.COMPLETED,
                scheduledAt, createdAt, now, null, null, null, lastError);
    }

    /**
     * RUNNING -> FAILED: releases the lease and parks the job until {@code retryAt}. Private
     * because only {@link #attemptFailed} may choose it: every other path to FAILED would skip
     * the retry budget.
     */
    private Job failedWithRetryAt(Instant retryAt, String error, Instant now) {
        state.requireTransitionTo(JobState.FAILED);
        return new Job(id, type, payload, priority, maxRetries, attempt, JobState.FAILED,
                Objects.requireNonNull(retryAt, "retryAt"), createdAt, now, null, null, null,
                error);
    }

    /** RUNNING -> DEAD: releases the lease and dead-letters the job. */
    public Job deadLettered(String error, Instant now) {
        state.requireTransitionTo(JobState.DEAD);
        return new Job(id, type, payload, priority, maxRetries, attempt, JobState.DEAD,
                scheduledAt, createdAt, now, null, null, null, error);
    }

    /**
     * RUNNING -> PENDING: the worker vanished and its lease expired, so the job goes back on the
     * queue. The attempt counter is left alone. The attempt happened, and we simply never heard how
     * it ended, and charging it against the retry budget is the safe reading. The sweeper calls
     * {@link #reclaimed}, which applies this only while a retry is left.
     */
    public Job leaseExpired(Instant now) {
        state.requireTransitionTo(JobState.PENDING);
        return new Job(id, type, payload, priority, maxRetries, attempt, JobState.PENDING,
                now, createdAt, now, null, null, null,
                "Visibility timeout expired; job reclaimed from worker " + lockedBy);
    }

    /**
     * What the sweeper does with a RUNNING job whose lease lapsed: back to PENDING for another
     * attempt, unless that attempt was the last one the retry budget allowed, in which case the
     * job is dead-lettered.
     *
     * <p>A worker that dies mid-job never reports a failure, so {@link #attemptFailed}, the only
     * other place the budget decides what happens to a job, never runs for it. Without this check a
     * job that reliably kills its worker (an out-of-memory kill, a native crash) would be reclaimed
     * and re-claimed forever, each pass taking down another worker.
     */
    public Job reclaimed(Instant now) {
        if (retriesExhausted()) {
            return deadLettered("Visibility timeout expired on the last permitted attempt; worker "
                    + lockedBy + " never reported back", now);
        }
        return leaseExpired(now);
    }

    /**
     * What the store does with a RUNNING job whose attempt failed: FAILED until {@code retryAt},
     * unless that attempt was the last one the retry budget allowed, in which case the job is
     * dead-lettered and {@code retryAt} goes unused. Either way {@code error} becomes the last
     * error.
     *
     * <p>This is the budget check for a failure the worker reports, as {@link #reclaimed} is for
     * an attempt whose worker never reported back. The store applies it to the row it holds under
     * the lease, so the decision and the write are one atomic step.
     */
    public Job attemptFailed(String error, Instant retryAt, Instant now) {
        if (retriesExhausted()) {
            return deadLettered(error, now);
        }
        return failedWithRetryAt(retryAt, error, now);
    }

    /** SCHEDULED/FAILED -> PENDING: the delay or backoff elapsed and the job is claimable again. */
    public Job promotedToPending(Instant now) {
        state.requireTransitionTo(JobState.PENDING);
        return new Job(id, type, payload, priority, maxRetries, attempt, JobState.PENDING,
                scheduledAt, createdAt, now, null, null, null, lastError);
    }

    /**
     * DEAD -> PENDING, on operator request. The retry budget is reset so the retried job gets a
     * full set of attempts rather than dying again on the first stumble.
     *
     * <p>The attempt count starts again from 0, so the next claim is attempt 1 again. Its new lease
     * id is what keeps a stalled earlier attempt 1 from recording over it.
     */
    public Job manuallyRetried(Instant now) {
        state.requireTransitionTo(JobState.PENDING);
        return new Job(id, type, payload, priority, maxRetries, 0, JobState.PENDING,
                now, createdAt, now, null, null, null, lastError);
    }
}
