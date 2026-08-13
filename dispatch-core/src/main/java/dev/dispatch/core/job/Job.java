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
     * True while {@code workerId} holds this job's visibility lease. Stores check this before
     * recording any result: a worker that stalled past its visibility timeout must not overwrite
     * whoever legitimately took the job over.
     */
    public boolean leaseHeldBy(String workerId) {
        return state == JobState.RUNNING && workerId.equals(lockedBy);
    }

    /** PENDING -> RUNNING: takes a visibility lease and counts the attempt. */
    public Job claimedBy(String workerId, Instant now, Duration visibilityTimeout) {
        state.requireTransitionTo(JobState.RUNNING);
        return new Job(id, type, payload, priority, maxRetries, attempt + 1, JobState.RUNNING,
                scheduledAt, createdAt, now, now.plus(visibilityTimeout),
                Objects.requireNonNull(workerId, "workerId"), lastError);
    }

    /** RUNNING -> COMPLETED: releases the lease. */
    public Job completed(Instant now) {
        state.requireTransitionTo(JobState.COMPLETED);
        return new Job(id, type, payload, priority, maxRetries, attempt, JobState.COMPLETED,
                scheduledAt, createdAt, now, null, null, lastError);
    }

    /** RUNNING -> FAILED: releases the lease and parks the job until {@code retryAt}. */
    public Job failedWithRetryAt(Instant retryAt, String error, Instant now) {
        state.requireTransitionTo(JobState.FAILED);
        return new Job(id, type, payload, priority, maxRetries, attempt, JobState.FAILED,
                Objects.requireNonNull(retryAt, "retryAt"), createdAt, now, null, null, error);
    }

    /** -> DEAD: the dead-letter transition, valid from every non-terminal state. */
    public Job deadLettered(String error, Instant now) {
        state.requireTransitionTo(JobState.DEAD);
        return new Job(id, type, payload, priority, maxRetries, attempt, JobState.DEAD,
                scheduledAt, createdAt, now, null, null, error);
    }

    /**
     * RUNNING -> PENDING: the worker vanished and its lease expired, so the job goes back on the
     * queue. The attempt counter is left alone — the attempt happened, we simply never heard how
     * it ended, and charging it against the retry budget is the safe reading.
     */
    public Job leaseExpired(Instant now) {
        state.requireTransitionTo(JobState.PENDING);
        return new Job(id, type, payload, priority, maxRetries, attempt, JobState.PENDING,
                now, createdAt, now, null, null,
                "Visibility timeout expired; job reclaimed from worker " + lockedBy);
    }

    /** SCHEDULED/FAILED -> PENDING: the delay or backoff elapsed and the job is claimable again. */
    public Job promotedToPending(Instant now) {
        state.requireTransitionTo(JobState.PENDING);
        return new Job(id, type, payload, priority, maxRetries, attempt, JobState.PENDING,
                scheduledAt, createdAt, now, null, null, lastError);
    }

    /**
     * DEAD -> PENDING, on operator request. The retry budget is reset so the revived job gets a
     * full set of attempts rather than dying again on the first stumble.
     */
    public Job revivedForManualRetry(Instant now) {
        state.requireTransitionTo(JobState.PENDING);
        return new Job(id, type, payload, priority, maxRetries, 0, JobState.PENDING,
                now, createdAt, now, null, null, lastError);
    }
}
