package dev.dispatch.core.job;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lifecycle of a job, with the legal transitions modelled explicitly.
 *
 * <p>The legal moves are the constants of {@link Transition}. The diagram is in
 * {@code docs/architecture/job-lifecycle.md}, and {@code LifecycleDocTest} keeps it in step.
 *
 * <p>The distinction worth internalising: {@link #SCHEDULED} means "delayed, never attempted",
 * {@link #FAILED} means "attempted, waiting out a backoff before the next attempt", and
 * {@link #DEAD} means "gave up" (the dead-letter state). Both SCHEDULED and FAILED carry a
 * {@code scheduledAt} in the future; a sweeper promotes them to PENDING once that time passes.
 */
public enum JobState {

    /** Claimable right now (subject to {@code scheduledAt <= now}). */
    PENDING,

    /** Submitted with a future {@code scheduledAt}; not yet attempted. */
    SCHEDULED,

    /** Claimed by a worker. Holds a visibility lease that expires at {@code lockedUntil}. */
    RUNNING,

    /** Finished successfully. Terminal. */
    COMPLETED,

    /** An attempt failed and a retry is pending, once the backoff at {@code scheduledAt} elapses. */
    FAILED,

    /**
     * Retries exhausted, or failed permanently. The dead-letter state; only a manual retry
     * leaves it.
     */
    DEAD;

    /** States a job in this state may legally move to, derived from {@link Transition}. */
    public Set<JobState> allowedTransitions() {
        return Allowed.TABLE.get(this);
    }

    /**
     * Built on first use rather than in JobState's static initializer, since Transition's
     * constants read JobState's: whichever class loads first, both are ready by then.
     */
    private static final class Allowed {
        static final Map<JobState, Set<JobState>> TABLE = build();

        private static Map<JobState, Set<JobState>> build() {
            Map<JobState, Set<JobState>> allowed = new EnumMap<>(JobState.class);
            for (JobState state : values()) {
                allowed.put(state, EnumSet.noneOf(JobState.class));
            }
            for (Transition transition : Transition.values()) {
                for (JobState from : transition.from()) {
                    allowed.get(from).add(transition.to());
                }
            }
            allowed.replaceAll((state, targets) -> Collections.unmodifiableSet(targets));
            return Collections.unmodifiableMap(allowed);
        }
    }

    /**
     * True when a job in this state may be cancelled. It has not started. Once a worker
     * holds the lease there is nothing safe to cancel from outside.
     */
    public boolean isCancellable() {
        return this == PENDING || this == SCHEDULED;
    }

    /** The states accepted by {@link #isCancellable()}, derived from it for refusal reporting. */
    public static Set<JobState> cancellableStates() {
        return CANCELLABLE;
    }

    /** The states a manual retry accepts: DEAD alone. The single source for its refusals. */
    public static Set<JobState> manuallyRetryableStates() {
        return MANUALLY_RETRYABLE;
    }

    private static final Set<JobState> CANCELLABLE = Arrays.stream(values())
            .filter(JobState::isCancellable)
            .collect(Collectors.toUnmodifiableSet());

    private static final Set<JobState> MANUALLY_RETRYABLE = Set.of(DEAD);

    /** A mutable count map with every state present at zero, so absent states read as 0, not null. */
    public static Map<JobState, Long> zeroCounts() {
        Map<JobState, Long> counts = new EnumMap<>(JobState.class);
        for (JobState state : values()) {
            counts.put(state, 0L);
        }
        return counts;
    }
}
