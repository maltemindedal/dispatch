package dev.dispatch.core.store.memory;

import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.store.JobFilter;
import dev.dispatch.core.store.JobRows;
import dev.dispatch.core.store.JobSelection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Job rows in a map, for tests and single-process use.
 *
 * <p>Concurrency model: one {@link ReentrantLock} spans each exclusive scope, which gives the same
 * atomicity a database transaction does. Because the lock is reentrant and held for the whole
 * scope, a scope sees a still world — the same guarantee {@code FOR UPDATE} buys the JDBC adapter.
 *
 * <p>Three differences from a database are worth knowing. There is no rollback: a scope that throws
 * leaves behind whatever it had already written, which is acceptable because
 * {@code dev.dispatch.core.store.JobStore} never writes before its last decision. Reads take the
 * same lock writes do, so a listing sees a consistent world but contends with claiming — the right
 * trade for a store whose job is tests and demos, and the reason this one is not the production
 * choice. And selections are answered by scanning and sorting the whole map — O(n log n) per claim,
 * the first thing to fix at scale, but it renders exactly the same {@link JobSelection} the SQL
 * adapter does, which matters more: the two adapters are meant to be indistinguishable, and one
 * contract suite proves it.
 */
public final class InMemoryJobRows implements JobRows {

    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public <R> R inExclusiveScope(Function<Scope, R> work) {
        lock.lock();
        try {
            return work.apply(new MapScope());
        } finally {
            lock.unlock();
        }
    }

    private final class MapScope implements Scope {

        @Override
        public void insert(Job job) {
            jobs.put(job.id(), job);
        }

        @Override
        public Optional<Job> byId(UUID id) {
            // Nothing to wait for: the scope already holds the only lock there is.
            return read(id);
        }

        @Override
        public Optional<Job> read(UUID id) {
            return Optional.ofNullable(jobs.get(id));
        }

        @Override
        public List<Job> matching(JobSelection selection, Instant now, int limit) {
            // Nothing to skip: no other scope can be holding rows while this one runs.
            return jobs.values().stream()
                    .filter(job -> selection.matches(job, now))
                    .sorted(selection.comparator())
                    .limit(limit)
                    .toList();
        }

        @Override
        public void write(List<Job> updated) {
            updated.forEach(job -> jobs.put(job.id(), job));
        }

        @Override
        public void delete(UUID id) {
            jobs.remove(id);
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
                    .sorted(JobSelection.comparatorFor(JobSelection.LISTING_ORDER))
                    .skip(filter.offset())
                    .limit(filter.limit())
                    .toList();
        }

        @Override
        public Map<JobState, Long> countsByState() {
            Map<JobState, Long> counts = JobState.zeroCounts();
            jobs.values().forEach(job -> counts.merge(job.state(), 1L, Long::sum));
            return counts;
        }

        @Override
        public void deleteAll() {
            jobs.clear();
        }
    }
}
