package dev.dispatch.core.store;

import dev.dispatch.core.job.Job;
import java.time.Instant;
import java.util.Comparator;
import java.util.function.Function;

/**
 * A field of a job that a {@link JobSelection} may sort or filter on.
 *
 * <p>This exists so an ordering rule can be <em>stated</em> once and <em>rendered</em> twice. Each
 * constant carries a {@link Comparator} for adapters that sort in the JVM; adapters that sort in a
 * database map the constant to a column of their own. Neither adapter gets to decide what the order
 * is — {@link JobSelection} already did.
 *
 * <p>Fields that carry a timestamp also expose it, which is what lets a selection say "due when this
 * field is at or before now" without naming a specific column.
 */
public enum JobField {

    /** Higher runs first — the only field a selection normally sorts descending. */
    PRIORITY(Comparator.comparingInt(Job::priority), null),

    /** Earliest instant a job may be claimed; also carries the retry backoff. */
    SCHEDULED_AT(Comparator.comparing(Job::scheduledAt), Job::scheduledAt),

    /** Submission time. */
    CREATED_AT(Comparator.comparing(Job::createdAt), Job::createdAt),

    /** Visibility deadline while RUNNING; null in every other state. */
    LOCKED_UNTIL(
            Comparator.comparing(Job::lockedUntil, Comparator.nullsLast(Comparator.naturalOrder())),
            Job::lockedUntil),

    /** Stable identity. Only ever a final tiebreak, so results do not depend on storage order. */
    ID(Comparator.comparing(Job::id), null);

    private final Comparator<Job> ascending;
    private final Function<Job, Instant> timestamp;

    JobField(Comparator<Job> ascending, Function<Job, Instant> timestamp) {
        this.ascending = ascending;
        this.timestamp = timestamp;
    }

    Comparator<Job> ascending() {
        return ascending;
    }

    /** True if this field holds a timestamp, and so may be used as a selection's due field. */
    public boolean isTimestamp() {
        return timestamp != null;
    }

    /**
     * @return this field's instant on {@code job}
     * @throws IllegalStateException if the field does not hold a timestamp
     */
    public Instant timestampOf(Job job) {
        if (timestamp == null) {
            throw new IllegalStateException(this + " does not hold a timestamp");
        }
        return timestamp.apply(job);
    }
}
