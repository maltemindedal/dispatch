package dev.dispatch.core.job;

import java.util.Objects;
import java.util.UUID;

/**
 * Names one lease: one worker's hold on one job for one attempt.
 *
 * <p>A claim hands each job out under a lease, and the snapshot it returns carries it
 * ({@link Job#lease()}). To record how the attempt ended, the worker hands the lease back, and the
 * store applies the result only while the job is still {@linkplain Job#heldUnder held under} it.
 * A result under any other lease is a lost lease. That covers another worker, and also an earlier
 * attempt of the same worker, which is why the worker id alone is not enough.
 *
 * <p>It says whose hold it is, not how long the hold lasts. The deadline stays on the row as
 * {@code lockedUntil}.
 *
 * @param jobId    the job held
 * @param workerId the worker holding it, as stored in {@code lockedBy}
 * @param attempt  the attempt it was claimed for; 1 on the first claim
 */
public record Lease(UUID jobId, String workerId, int attempt) {

    public Lease {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(workerId, "workerId");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1: " + attempt);
        }
    }
}
