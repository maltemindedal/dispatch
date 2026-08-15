package dev.dispatch.core.store;

import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Exclusive access to job rows — the storage seam, and nothing more.
 *
 * <p>This is deliberately dumber than the {@link JobStore} above it. An adapter here answers
 * "hold these rows exclusively" and "write these rows back"; it decides nothing. Which rows are
 * claimable, what order they come in, when a cancel is refused, whether a worker still holds its
 * lease — all of that is {@link JobStore}'s, stated once, so two adapters cannot disagree about it.
 * They used to, and did.
 *
 * <p>Two adapters exist: a map under a lock, and SQL rows under {@code FOR UPDATE}. That is what
 * makes this a real seam rather than a hypothetical one.
 *
 * <h2>What an implementation must guarantee</h2>
 * <ol>
 *   <li><b>Exclusive scopes.</b> Work passed to {@link #inExclusiveScope} runs as one atomic unit
 *       against concurrent callers, across every thread <em>and every process</em> sharing the
 *       storage. Everything the scope read stays as it was read until the work returns. This is the
 *       one rule the whole design rests on.</li>
 *   <li><b>All or nothing.</b> If the work throws, nothing it wrote is visible.</li>
 *   <li><b>One failure vocabulary.</b> Storage failures — a lost connection, a broken schema —
 *       surface as {@link JobStoreException}, whatever the underlying technology.</li>
 * </ol>
 */
public interface JobRows extends AutoCloseable {

    /**
     * Runs {@code work} with exclusive access to the rows it touches, and returns its result.
     *
     * <p>Implementations acquire whatever they need — a lock, a transaction — before the work runs
     * and release it after, committing on a normal return and discarding on a throw.
     */
    <R> R inExclusiveScope(Function<Scope, R> work);

    /**
     * The rows, for the duration of one exclusive scope. Every method is a primitive: it moves rows,
     * it never decides anything about them.
     *
     * <p>A scope is valid only inside the {@link #inExclusiveScope} call that produced it. Holding
     * one past that is a programming error, and an implementation may fail loudly rather than
     * quietly return stale rows.
     */
    interface Scope {

        /** Stores a brand-new job. The caller has already built it. */
        void insert(Job job);

        /**
         * Takes one row, if it exists.
         *
         * <p><b>Waits</b> for the row rather than skipping it: an operator action must answer about
         * the state a job is actually in, not report "not found" because someone else held it for a
         * moment.
         */
        Optional<Job> byId(UUID id);

        /**
         * Takes up to {@code limit} rows belonging to {@code selection} at {@code now}, in the
         * selection's order.
         *
         * <p><b>Skips</b> rows another caller already holds rather than waiting for them — that is
         * what lets N instances claim from one queue without a coordinator, each walking past the
         * rows its peers are taking. A caller therefore gets "up to {@code limit} rows that were
         * free", never a guarantee it saw every matching row.
         *
         * @return the matching rows in {@code selection}'s order, possibly empty, never null
         */
        List<Job> matching(JobSelection selection, Instant now, int limit);

        /** Replaces rows by id with these snapshots. Fields a transition cannot change are ignored. */
        void write(List<Job> jobs);

        /** Removes one row. */
        void delete(UUID id);

        /** Reads without taking anything exclusively; ordered by {@link JobSelection#LISTING_ORDER}. */
        List<Job> list(JobFilter filter);

        /** Reads one row without taking it exclusively. */
        Optional<Job> read(UUID id);

        /** Row count per state. Every state present; states with no rows map to zero. */
        Map<JobState, Long> countsByState();

        /** Removes every row, regardless of state. */
        void deleteAll();
    }

    @Override
    default void close() {
        // Most adapters hold nothing that needs releasing; the JDBC one does not own its DataSource.
    }
}
