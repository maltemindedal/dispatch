package dev.dispatch.core.store.memory;

import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.store.JobFilter;
import dev.dispatch.core.store.JobStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * A {@link JobStore} backed by a map, for tests and single-process use.
 *
 * <p>Concurrency model: every state-changing operation runs under one {@link ReentrantLock}, which
 * gives the same atomicity a database transaction does. Reads go straight at the
 * {@link ConcurrentHashMap} without locking, so a listing may catch the queue mid-transition —
 * fine for a dashboard, and it keeps claiming off the read path.
 *
 * <p>{@link #claim} scans and sorts the whole map rather than maintaining a priority index. That is
 * O(n log n) per claim and would be the first thing to fix at scale, but it mirrors the ORDER BY in
 * the SQL implementation exactly, which matters more here: the two stores are meant to be
 * behaviourally identical, and they share one contract test suite that proves it.
 */
public final class InMemoryJobStore implements JobStore {

    /**
     * Claim order, shared conceptually with the SQL {@code ORDER BY priority DESC, scheduled_at,
     * created_at}. Id is the final tiebreak purely so results are deterministic.
     */
    static final Comparator<Job> CLAIM_ORDER = Comparator
            .comparingInt(Job::priority).reversed()
            .thenComparing(Job::scheduledAt)
            .thenComparing(Job::createdAt)
            .thenComparing(Job::id);

    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Supplier<UUID> idGenerator;

    public InMemoryJobStore() {
        this(UUID::randomUUID);
    }

    /** Overload taking the id source, so tests can make ids predictable. */
    public InMemoryJobStore(Supplier<UUID> idGenerator) {
        this.idGenerator = idGenerator;
    }

    @Override
    public Job insert(JobSubmission submission, Instant now) {
        Job job = Job.newJob(idGenerator.get(), submission, now);
        lock.lock();
        try {
            jobs.put(job.id(), job);
        } finally {
            lock.unlock();
        }
        return job;
    }

    @Override
    public Optional<Job> find(UUID id) {
        return Optional.ofNullable(jobs.get(id));
    }

    @Override
    public List<Job> list(JobFilter filter) {
        Stream<Job> stream = jobs.values().stream();
        if (filter.state() != null) {
            stream = stream.filter(job -> job.state() == filter.state());
        }
        if (filter.type() != null) {
            stream = stream.filter(job -> job.type().equals(filter.type()));
        }
        return stream
                .sorted(Comparator.comparing(Job::createdAt).thenComparing(Job::id).reversed())
                .skip(filter.offset())
                .limit(filter.limit())
                .toList();
    }

    @Override
    public List<Job> claim(String workerId, int limit, Duration visibilityTimeout, Instant now) {
        if (limit <= 0) {
            return List.of();
        }
        lock.lock();
        try {
            List<Job> claimable = jobs.values().stream()
                    .filter(job -> job.state() == JobState.PENDING && job.dueAt(now))
                    .sorted(CLAIM_ORDER)
                    .limit(limit)
                    .toList();

            List<Job> claimed = new ArrayList<>(claimable.size());
            for (Job job : claimable) {
                Job running = job.claimedBy(workerId, now, visibilityTimeout);
                jobs.put(running.id(), running);
                claimed.add(running);
            }
            return List.copyOf(claimed);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Job> complete(UUID id, String workerId, Instant now) {
        return updateIfLeaseHeld(id, workerId, job -> job.completed(now));
    }

    @Override
    public Optional<Job> fail(UUID id, String workerId, String error, Instant retryAt, Instant now) {
        return updateIfLeaseHeld(id, workerId, job -> job.failedWithRetryAt(retryAt, error, now));
    }

    @Override
    public Optional<Job> deadLetter(UUID id, String workerId, String error, Instant now) {
        return updateIfLeaseHeld(id, workerId, job -> job.deadLettered(error, now));
    }

    @Override
    public int promoteDueJobs(Instant now, int limit) {
        if (limit <= 0) {
            return 0;
        }
        lock.lock();
        try {
            List<Job> due = jobs.values().stream()
                    .filter(job -> job.state() == JobState.SCHEDULED || job.state() == JobState.FAILED)
                    .filter(job -> job.dueAt(now))
                    .sorted(CLAIM_ORDER)
                    .limit(limit)
                    .toList();
            due.forEach(job -> jobs.put(job.id(), job.promotedToPending(now)));
            return due.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int reclaimExpiredLeases(Instant now, int limit) {
        if (limit <= 0) {
            return 0;
        }
        lock.lock();
        try {
            List<Job> expired = jobs.values().stream()
                    .filter(job -> job.leaseExpiredAt(now))
                    .limit(limit)
                    .toList();
            expired.forEach(job -> jobs.put(job.id(), job.leaseExpired(now)));
            return expired.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean cancel(UUID id) {
        lock.lock();
        try {
            Job job = jobs.get(id);
            if (job == null || !job.state().isCancellable()) {
                return false;
            }
            jobs.remove(id);
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Job> requeueDeadJob(UUID id, Instant now) {
        lock.lock();
        try {
            Job job = jobs.get(id);
            if (job == null || job.state() != JobState.DEAD) {
                return Optional.empty();
            }
            Job revived = job.revivedForManualRetry(now);
            jobs.put(id, revived);
            return Optional.of(revived);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Map<JobState, Long> countsByState() {
        Map<JobState, Long> counts = JobState.zeroCounts();
        jobs.values().forEach(job -> counts.merge(job.state(), 1L, Long::sum));
        return counts;
    }

    @Override
    public long count() {
        return jobs.size();
    }

    /** Drops every job. Test convenience; there is deliberately no equivalent on the interface. */
    public void clear() {
        lock.lock();
        try {
            jobs.clear();
        } finally {
            lock.unlock();
        }
    }

    private Optional<Job> updateIfLeaseHeld(
            UUID id, String workerId, java.util.function.UnaryOperator<Job> transition) {
        lock.lock();
        try {
            Job job = jobs.get(id);
            if (job == null || !job.leaseHeldBy(workerId)) {
                return Optional.empty();
            }
            Job updated = transition.apply(job);
            jobs.put(id, updated);
            return Optional.of(updated);
        } finally {
            lock.unlock();
        }
    }
}
