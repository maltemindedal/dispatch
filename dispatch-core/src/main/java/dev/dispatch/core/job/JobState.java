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
 * <pre>
 *   submit(now)      ┌──────────────┐  claim   ┌─────────┐  success   ┌───────────┐
 *   ────────────────▶│   PENDING    │─────────▶│ RUNNING │───────────▶│ COMPLETED │
 *                    └──────────────┘          └─────────┘            └───────────┘
 *                      ▲     ▲   ▲                 │  │
 *   submit(future)     │     │   │ visibility      │  │ failure, retries left
 *   ┌───────────┐ due  │     │   │ timeout         │  ▼
 *   │ SCHEDULED │──────┘     │   └─────────────────┤ ┌────────┐  backoff elapsed
 *   └───────────┘            │                     │ │ FAILED │────────────────────┐
 *                            │                     │ └────────┘                    │
 *                            │                     │  │                            │
 *                            │  manual retry       │  │ failure, retries exhausted │
 *                            │  ┌──────┐           │  ▼                            │
 *                            └──│ DEAD │◀──────────┴─────                          │
 *                               └──────┘                                           │
 *                            ▲                                                     │
 *                            └─────────────────────────────────────────────────────┘
 * </pre>
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

    /** Retries exhausted (or manually killed). The dead-letter state; only a manual retry revives it. */
    DEAD;

    private static final Map<JobState, Set<JobState>> ALLOWED;

    static {
        Map<JobState, Set<JobState>> allowed = new EnumMap<>(JobState.class);
        // Claimed by a worker.
        allowed.put(PENDING, EnumSet.of(RUNNING, DEAD));
        // Its delay elapsed (sweeper), or it was cancelled outright.
        allowed.put(SCHEDULED, EnumSet.of(PENDING, DEAD));
        // Succeeded / failed with retries left / failed for good / visibility lease expired.
        allowed.put(RUNNING, EnumSet.of(COMPLETED, FAILED, DEAD, PENDING));
        // Backoff elapsed (sweeper), or the retry budget was revoked.
        allowed.put(FAILED, EnumSet.of(PENDING, DEAD));
        // Revived by an operator via POST /jobs/{id}/retry.
        allowed.put(DEAD, EnumSet.of(PENDING));
        // Terminal, no way back. A re-run is a new job.
        allowed.put(COMPLETED, EnumSet.noneOf(JobState.class));
        ALLOWED = Collections.unmodifiableMap(allowed);
    }

    /** States a job in this state may legally move to. */
    public Set<JobState> allowedTransitions() {
        return ALLOWED.get(this);
    }

    public boolean canTransitionTo(JobState next) {
        return ALLOWED.get(this).contains(next);
    }

    /**
     * @throws IllegalJobTransitionException if the move is not part of the state machine
     */
    public void requireTransitionTo(JobState next) {
        if (!canTransitionTo(next)) {
            throw new IllegalJobTransitionException(this, next);
        }
    }

    /**
     * True when a job in this state may be cancelled — i.e. it has not started. Once a worker
     * holds the lease there is nothing safe to cancel from outside.
     */
    public boolean isCancellable() {
        return this == PENDING || this == SCHEDULED;
    }

    /** The states {@link #isCancellable()} accepts — derived from it, for reporting refusals. */
    public static Set<JobState> cancellableStates() {
        return CANCELLABLE;
    }

    private static final Set<JobState> CANCELLABLE = Arrays.stream(values())
            .filter(JobState::isCancellable)
            .collect(Collectors.toUnmodifiableSet());

    /** A mutable count map with every state present at zero, so absent states read as 0, not null. */
    public static Map<JobState, Long> zeroCounts() {
        Map<JobState, Long> counts = new EnumMap<>(JobState.class);
        for (JobState state : values()) {
            counts.put(state, 0L);
        }
        return counts;
    }
}
