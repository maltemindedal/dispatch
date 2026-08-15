package dev.dispatch.core.store;

import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Which rows an operation wants, and in what order — stated once, rendered by every adapter.
 *
 * <p>Before this existed, each rule lived twice: "claimable" was a Java predicate in the in-memory
 * store and a {@code WHERE} clause in the SQL one, and the claim order was a {@code Comparator} in
 * one and an {@code ORDER BY} in the other. Two of those pairs had already drifted apart — the
 * in-memory claim order carried an id tiebreak the SQL did not, and the in-memory expired-lease
 * sweep had no order at all. Nothing but the contract suite was watching, and the contract suite
 * did not cover either case.
 *
 * <p>Now the rule has one home. An adapter is handed a selection and renders it: in the JVM via
 * {@link #matches} and {@link #comparator}, or in a database by mapping each {@link JobField} to a
 * column. Adding a tiebreak here changes both adapters at once, which is the whole point.
 *
 * @param name     what this selection is called, for logs and error messages
 * @param states   the states a row must be in; never empty
 * @param dueBy    the timestamp field that must be at or before {@code now}
 * @param ordering the sort, most significant first; must end in a total tiebreak
 */
public record JobSelection(
        String name,
        Set<JobState> states,
        JobField dueBy,
        List<Order> ordering) {

    /**
     * The order claiming and promotion both use: highest priority first, then the job that has been
     * waiting longest, then oldest, then id.
     *
     * <p>The trailing {@link JobField#ID} makes the order <em>total</em>: without it, jobs identical
     * on priority, schedule and creation time come back in whatever order storage felt like, so a
     * capped claim could return a different subset every call and starve one of them. It does not
     * make the order identical everywhere — Java compares UUIDs as signed longs and PostgreSQL
     * compares them as unsigned bytes, so tied jobs may come back in a different sequence per
     * adapter. Each adapter is self-consistent, which is the property a batch cap actually needs.
     */
    private static final List<Order> CLAIM_ORDER = List.of(
            Order.descending(JobField.PRIORITY),
            Order.ascending(JobField.SCHEDULED_AT),
            Order.ascending(JobField.CREATED_AT),
            Order.ascending(JobField.ID));

    /** Ready to run right now: PENDING and no longer held back by {@code scheduledAt}. */
    public static final JobSelection CLAIMABLE = new JobSelection(
            "claimable", EnumSet.of(JobState.PENDING), JobField.SCHEDULED_AT, CLAIM_ORDER);

    /**
     * Delayed or backing off, and the wait is over. Promoting these to PENDING is what makes
     * scheduled jobs run and retry backoff expire.
     */
    public static final JobSelection DUE = new JobSelection(
            "due", EnumSet.of(JobState.SCHEDULED, JobState.FAILED), JobField.SCHEDULED_AT,
            CLAIM_ORDER);

    /**
     * RUNNING with a lapsed lease — the crash-recovery path. Ordered by lease age so a capped sweep
     * always takes the longest-abandoned jobs first, rather than an arbitrary subset.
     */
    public static final JobSelection EXPIRED_LEASE = new JobSelection(
            "expired lease", EnumSet.of(JobState.RUNNING), JobField.LOCKED_UNTIL,
            List.of(Order.ascending(JobField.LOCKED_UNTIL), Order.ascending(JobField.ID)));

    /**
     * How {@code JobStore.list} pages: newest first, id as the tiebreak so paging can neither repeat
     * nor skip a row. Shared by every adapter for the same reason the selections are.
     */
    public static final List<Order> LISTING_ORDER = List.of(
            Order.descending(JobField.CREATED_AT),
            Order.descending(JobField.ID));

    public JobSelection {
        Objects.requireNonNull(name, "name");
        if (states.isEmpty()) {
            throw new IllegalArgumentException("a selection must name at least one state");
        }
        // EnumSet, not Set.copyOf: iteration follows the lifecycle order, so an adapter that
        // renders the states into a query produces the same text every time.
        states = Collections.unmodifiableSet(EnumSet.copyOf(states));
        Objects.requireNonNull(dueBy, "dueBy");
        if (!dueBy.isTimestamp()) {
            throw new IllegalArgumentException("dueBy must hold a timestamp: " + dueBy);
        }
        ordering = List.copyOf(ordering);
        if (ordering.isEmpty()) {
            throw new IllegalArgumentException("a selection must be ordered");
        }
        if (ordering.get(ordering.size() - 1).field() != JobField.ID) {
            throw new IllegalArgumentException(
                    "ordering must end in ID so it is total: " + ordering);
        }
    }

    /** True when {@code job} belongs to this selection at {@code now}. */
    public boolean matches(Job job, Instant now) {
        return states.contains(job.state()) && !dueBy.timestampOf(job).isAfter(now);
    }

    /** This selection's ordering as a comparator, for adapters that sort in the JVM. */
    public Comparator<Job> comparator() {
        return comparatorFor(ordering);
    }

    /** Turns an ordering into a comparator. Also serves {@link #LISTING_ORDER}. */
    public static Comparator<Job> comparatorFor(List<Order> ordering) {
        Comparator<Job> comparator = null;
        for (Order order : ordering) {
            Comparator<Job> next = order.comparator();
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        return comparator;
    }

    /**
     * One clause of an ordering.
     *
     * @param field      what to sort on
     * @param descending true for highest first
     */
    public record Order(JobField field, boolean descending) {

        public Order {
            Objects.requireNonNull(field, "field");
        }

        public static Order ascending(JobField field) {
            return new Order(field, false);
        }

        public static Order descending(JobField field) {
            return new Order(field, true);
        }

        Comparator<Job> comparator() {
            return descending ? field.ascending().reversed() : field.ascending();
        }
    }
}
