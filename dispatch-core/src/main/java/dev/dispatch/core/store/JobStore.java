package dev.dispatch.core.store;

import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.job.JobSubmission;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Where jobs live, and the only place claiming happens.
 *
 * <p>This is the seam that makes the engine storage-agnostic: {@code InMemoryJobStore} uses a lock
 * over a map, {@code JdbcJobStore} uses {@code SELECT ... FOR UPDATE SKIP LOCKED}. The worker pool
 * cannot tell them apart, and neither can the API.
 *
 * <h2>What an implementation must guarantee</h2>
 * <ol>
 *   <li><b>Exclusive claims.</b> A given job is handed to at most one caller of {@link #claim} at
 *       a time, across every thread <em>and every process</em> sharing the store. This is the one
 *       rule the whole design rests on.</li>
 *   <li><b>Claim ordering.</b> Highest {@code priority} first, then earliest {@code scheduledAt},
 *       then earliest {@code createdAt}. Both implementations sort identically so tests written
 *       against one hold for the other.</li>
 *   <li><b>Lease ownership.</b> {@link #complete}, {@link #fail} and {@link #deadLetter} apply only
 *       if the named worker still holds the lease, and return {@link Optional#empty()} otherwise.
 *       That is what stops a worker that stalled past its visibility timeout from stomping on the
 *       worker that legitimately took the job over.</li>
 *   <li><b>Atomic transitions.</b> Each method is a single atomic unit against concurrent callers.</li>
 * </ol>
 *
 * <p>All methods take {@code now} explicitly rather than reading the clock, so tests can drive
 * time forward without sleeping.
 */
public interface JobStore extends AutoCloseable {

    /** Persists a new job, PENDING if due now or SCHEDULED if {@code scheduledAt} is in the future. */
    Job insert(JobSubmission submission, Instant now);

    Optional<Job> find(UUID id);

    List<Job> list(JobFilter filter);

    /**
     * Atomically takes up to {@code limit} claimable jobs — state PENDING with
     * {@code scheduledAt <= now} — moving each to RUNNING with a lease held by {@code workerId}
     * until {@code now + visibilityTimeout}.
     *
     * @return the claimed jobs in execution order, possibly empty, never null
     */
    List<Job> claim(String workerId, int limit, Duration visibilityTimeout, Instant now);

    /** RUNNING -> COMPLETED. Empty if {@code workerId} no longer holds the lease. */
    Optional<Job> complete(UUID id, String workerId, Instant now);

    /** RUNNING -> FAILED, parked until {@code retryAt}. Empty if the lease was lost. */
    Optional<Job> fail(UUID id, String workerId, String error, Instant retryAt, Instant now);

    /** RUNNING -> DEAD. Empty if the lease was lost. */
    Optional<Job> deadLetter(UUID id, String workerId, String error, Instant now);

    /**
     * Promotes SCHEDULED and FAILED jobs whose {@code scheduledAt} has arrived to PENDING. This is
     * what actually makes delayed jobs and retry backoff take effect.
     *
     * @return how many jobs were promoted
     */
    int promoteDueJobs(Instant now, int limit);

    /**
     * Returns RUNNING jobs whose visibility lease expired to PENDING — the crash-recovery path.
     * A worker that died mid-job leaves its row RUNNING forever otherwise.
     *
     * @return how many leases were reclaimed
     */
    int reclaimExpiredLeases(Instant now, int limit);

    /**
     * Cancels a job that has not started: only PENDING and SCHEDULED jobs can be cancelled, and
     * cancelling removes the row (the lifecycle deliberately has no CANCELLED state).
     *
     * @return true if a job was removed
     */
    boolean cancel(UUID id);

    /** DEAD -> PENDING with a fresh retry budget. Empty if the job is missing or is not DEAD. */
    Optional<Job> requeueDeadJob(UUID id, Instant now);

    /** Current queue depth per state. Missing entries count as zero. */
    Map<JobState, Long> countsByState();

    /** Total rows, across every state. */
    long count();

    @Override
    default void close() {
        // Most stores hold nothing that needs releasing; the JDBC one does not own its DataSource.
    }
}
