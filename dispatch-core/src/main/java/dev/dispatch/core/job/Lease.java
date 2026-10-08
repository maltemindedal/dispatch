package dev.dispatch.core.job;

import java.util.Objects;
import java.util.UUID;

/**
 * Names one lease: a worker's hold on one job, taken by one claim.
 *
 * <p>A claim hands each job out under a lease, and the snapshot it returns carries it
 * ({@link Job#lease()}). To record how the attempt ended, the worker hands the lease back, and the
 * store applies the result only while the job is still {@linkplain Job#heldUnder held under} it.
 * A result under any other lease is a lost lease. That covers another worker, an earlier attempt of
 * the same worker, and an earlier lease on the same job with the same attempt number.
 *
 * <p>The last case is why every lease gets its own id. The worker id and attempt alone do not tell
 * leases apart: a manual retry resets the attempt count, so the same worker can claim the same job
 * as attempt 1 twice.
 *
 * <p>It says whose hold it is, not how long the hold lasts. The deadline stays on the row as
 * {@code lockedUntil}.
 *
 * @param jobId    the job held
 * @param workerId the worker holding it, as stored in {@code lockedBy}
 * @param attempt  the attempt it was claimed for; 1 on the first claim
 * @param leaseId  this lease's own id, drawn when a claim takes the job, as stored in
 *                 {@code leaseId}
 */
public record Lease(UUID jobId, String workerId, int attempt, UUID leaseId) {

    public Lease {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(workerId, "workerId");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1: " + attempt);
        }
        Objects.requireNonNull(leaseId, "leaseId");
    }
}
