package dev.dispatch.core.store;

import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobActionResult;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.store.memory.InMemoryJobRows;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Where jobs live, and the only place claiming happens.
 *
 * <p>Every rule about jobs in storage lives here, once: which rows are claimable and in what order
 * ({@link JobSelection}), when a cancel or a manual retry is refused, and the check that a worker
 * still holds its lease before its result is recorded. Underneath sits {@link JobRows}, a seam that
 * knows how to hold rows exclusively and nothing else — a map under a lock in one adapter, SQL rows
 * under {@code FOR UPDATE} in the other.
 *
 * <p>That split is the point. These rules used to be written once per adapter, and two of them had
 * quietly come apart: the in-memory claim order carried an id tiebreak the SQL did not, and the
 * in-memory expired-lease sweep applied its batch cap with no ordering at all. Both adapters passed
 * the contract suite, because the suite happened not to cover either case. A rule with one home
 * cannot drift.
 *
 * <h2>What callers can rely on</h2>
 * <ol>
 *   <li><b>Exclusive claims.</b> A given job is handed to at most one caller of {@link #claim} at a
 *       time, across every thread <em>and every process</em> sharing the storage.</li>
 *   <li><b>Claim ordering.</b> Highest {@code priority} first, then earliest {@code scheduledAt},
 *       then earliest {@code createdAt}, then id — one {@link JobSelection} every adapter renders,
 *       so a test written against one holds for the other. The id tiebreak makes the order total
 *       rather than universal; see {@link JobSelection} for what that does and does not buy.</li>
 *   <li><b>Lease ownership.</b> {@link #complete}, {@link #fail} and {@link #deadLetter} apply only
 *       if the named worker still holds the lease, and return {@link Optional#empty()} otherwise.
 *       That is what stops a worker that stalled past its visibility timeout from stomping on the
 *       worker that legitimately took the job over.</li>
 *   <li><b>Atomic transitions.</b> Each method is a single atomic unit against concurrent
 *       callers.</li>
 *   <li><b>One failure vocabulary.</b> Storage failures surface as {@link JobStoreException},
 *       whatever the underlying technology. "No such job" and "wrong state" are answers, not
 *       failures, and arrive as return values.</li>
 * </ol>
 *
 * <p>All methods take {@code now} explicitly rather than reading the clock, so tests can drive time
 * forward without sleeping.
 */
public final class JobStore implements AutoCloseable {

    private final JobRows rows;
    private final Supplier<UUID> idGenerator;

    private JobStore(JobRows rows, Supplier<UUID> idGenerator) {
        this.rows = Objects.requireNonNull(rows, "rows");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    /** A store over any adapter. */
    public static JobStore over(JobRows rows) {
        return new JobStore(rows, UUID::randomUUID);
    }

    /** Overload taking the id source, so tests can make ids predictable. */
    public static JobStore over(JobRows rows, Supplier<UUID> idGenerator) {
        return new JobStore(rows, idGenerator);
    }

    /** A store over a map, for tests and single-process use. */
    public static JobStore inMemory() {
        return over(new InMemoryJobRows());
    }

    /** Overload taking the id source, so tests can make ids predictable. */
    public static JobStore inMemory(Supplier<UUID> idGenerator) {
        return over(new InMemoryJobRows(), idGenerator);
    }

    // ---------------------------------------------------------------- producing

    /** Persists a new job, PENDING if due now or SCHEDULED if {@code scheduledAt} is in the future. */
    public Job insert(JobSubmission submission, Instant now) {
        Job job = Job.newJob(idGenerator.get(), submission, now);
        return rows.inExclusiveScope(scope -> {
            scope.insert(job);
            return job;
        });
    }

    // ---------------------------------------------------------------- querying

    public Optional<Job> find(UUID id) {
        return rows.inExclusiveScope(scope -> scope.read(id));
    }

    public List<Job> list(JobFilter filter) {
        return rows.inExclusiveScope(scope -> scope.list(filter));
    }

    /** Current queue depth per state. Every state is present; states with no rows map to zero. */
    public Map<JobState, Long> countsByState() {
        return rows.inExclusiveScope(JobRows.Scope::countsByState);
    }

    // ---------------------------------------------------------------- claiming

    /**
     * Atomically takes up to {@code limit} claimable jobs — {@link JobSelection#CLAIMABLE} —
     * moving each to RUNNING with a lease held by {@code workerId} until
     * {@code now + visibilityTimeout}.
     *
     * @return the claimed jobs in execution order, possibly empty, never null
     */
    public List<Job> claim(String workerId, int limit, Duration visibilityTimeout, Instant now) {
        Objects.requireNonNull(workerId, "workerId");
        if (limit <= 0) {
            return List.of();
        }
        return rows.inExclusiveScope(scope -> {
            List<Job> claimed = scope.matching(JobSelection.CLAIMABLE, now, limit).stream()
                    .map(job -> job.claimedBy(workerId, now, visibilityTimeout))
                    .toList();
            scope.write(claimed);
            return claimed;
        });
    }

    // ---------------------------------------------------------------- outcomes

    /** RUNNING -> COMPLETED. Empty if {@code workerId} no longer holds the lease. */
    public Optional<Job> complete(UUID id, String workerId, Instant now) {
        return transitionLeased(id, workerId, job -> job.completed(now));
    }

    /** RUNNING -> FAILED, parked until {@code retryAt}. Empty if the lease was lost. */
    public Optional<Job> fail(UUID id, String workerId, String error, Instant retryAt, Instant now) {
        return transitionLeased(id, workerId, job -> job.failedWithRetryAt(retryAt, error, now));
    }

    /** RUNNING -> DEAD. Empty if the lease was lost. */
    public Optional<Job> deadLetter(UUID id, String workerId, String error, Instant now) {
        return transitionLeased(id, workerId, job -> job.deadLettered(error, now));
    }

    // ---------------------------------------------------------------- sweeping

    /**
     * Promotes {@link JobSelection#DUE} jobs to PENDING. This is what actually makes delayed jobs
     * and retry backoff take effect.
     *
     * @return how many jobs were promoted
     */
    public int promoteDueJobs(Instant now, int limit) {
        return sweep(JobSelection.DUE, now, limit, job -> job.promotedToPending(now));
    }

    /**
     * Returns {@link JobSelection#EXPIRED_LEASE} jobs to PENDING — the crash-recovery path. A worker
     * that died mid-job leaves its row RUNNING forever otherwise.
     *
     * @return how many leases were reclaimed
     */
    public int reclaimExpiredLeases(Instant now, int limit) {
        return sweep(JobSelection.EXPIRED_LEASE, now, limit, job -> job.leaseExpired(now));
    }

    // ---------------------------------------------------------------- operator actions

    /**
     * Cancels a job that has not started — {@link JobState#isCancellable()} is the rule — and
     * removes the row (the lifecycle deliberately has no CANCELLED state). The decision and the
     * removal happen in one atomic step, so a refusal carries the state that was actually observed.
     *
     * @return {@link JobActionResult.Done} with the removed snapshot, or why not
     */
    public JobActionResult cancel(UUID id) {
        return decide(id, JobState.cancellableStates(), (scope, job) -> {
            scope.delete(id);
            return job;
        });
    }

    /**
     * DEAD -> PENDING with a fresh retry budget. The decision and the transition happen in one
     * atomic step.
     *
     * @return {@link JobActionResult.Done} with the revived snapshot, or why not
     */
    public JobActionResult requeueDeadJob(UUID id, Instant now) {
        return decide(id, JobState.revivableStates(), (scope, job) -> {
            Job revived = job.revivedForManualRetry(now);
            scope.write(List.of(revived));
            return revived;
        });
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Removes every job, regardless of state. For test harnesses and operator resets that own the
     * store outright; production code has no business calling it.
     */
    public void deleteAll() {
        rows.inExclusiveScope(scope -> {
            scope.deleteAll();
            return null;
        });
    }

    @Override
    public void close() {
        rows.close();
    }

    // ---------------------------------------------------------------- internals

    /**
     * Loads a job exclusively, refuses unless the caller still holds its lease, applies the
     * transition and writes it back — the one place that rule is stated.
     */
    private Optional<Job> transitionLeased(UUID id, String workerId, UnaryOperator<Job> transition) {
        Objects.requireNonNull(workerId, "workerId");
        return rows.inExclusiveScope(scope -> {
            Optional<Job> existing = scope.byId(id);
            // Refuse writes from a worker whose lease was reclaimed while it was still running.
            if (existing.isEmpty() || !existing.get().leaseHeldBy(workerId)) {
                return Optional.<Job>empty();
            }
            Job updated = transition.apply(existing.get());
            scope.write(List.of(updated));
            return Optional.of(updated);
        });
    }

    /** The shared shape of the two maintenance sweeps. */
    private int sweep(JobSelection selection, Instant now, int limit, UnaryOperator<Job> transition) {
        if (limit <= 0) {
            return 0;
        }
        return rows.inExclusiveScope(scope -> {
            List<Job> due = scope.matching(selection, now, limit);
            scope.write(due.stream().map(transition).toList());
            return due.size();
        });
    }

    /**
     * The shared shape of the two operator actions: hold the row, check it against the states the
     * action accepts, act. Holding first is what lets a refusal report the state that was really
     * there rather than one read a moment later.
     */
    private JobActionResult decide(UUID id, Set<JobState> allowed, Action action) {
        return rows.inExclusiveScope(scope -> {
            Optional<Job> existing = scope.byId(id);
            if (existing.isEmpty()) {
                return new JobActionResult.NotFound(id);
            }
            Job job = existing.get();
            if (!allowed.contains(job.state())) {
                return new JobActionResult.WrongState(job, allowed);
            }
            return new JobActionResult.Done(action.apply(scope, job));
        });
    }

    @FunctionalInterface
    private interface Action {
        /** @return the snapshot the caller should see: the removed row, or the transitioned one */
        Job apply(JobRows.Scope scope, Job job);
    }
}
