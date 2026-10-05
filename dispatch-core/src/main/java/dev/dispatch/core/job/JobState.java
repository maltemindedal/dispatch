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
 *  submit(now)     ┌─────────┐       claim        ┌─────────┐   success   ┌───────────┐
 * ────────────────▶│ PENDING │───────────────────▶│ RUNNING │────────────▶│ COMPLETED │
 *                  └─────────┘                    └─────────┘             └───────────┘
 *                    ▲ ▲ ▲ ▲                        │  │  │
 * submit(future)     │ │ │ │ visibility timeout     │  │  │ failure, retries left
 * ┌───────────┐  due │ │ │ └────────────────────────┘  │  ▼
 * │ SCHEDULED │──────┘ │ │                             │ ┌────────┐
 * └───────────┘        │ │  failure, retries exhausted │ │ FAILED │
 *                      │ │                ┌──────┐     │ └─────┬──┘
 *                      │ └────────────────│ DEAD │◀────┘       │
 *                      │   manual retry   └──────┘             │
 *                      │                      backoff elapsed  │
 *                      └───────────────────────────────────────┘
 * </pre>
 *
 * <p>The distinction worth internalising: {@link #SCHEDULED} means "delayed, never attempted",
 * {@link #FAILED} means "attempted, waiting out a backoff before the next attempt", and
 * {@link #DEAD} means "gave up" (the dead-letter state). Both SCHEDULED and FAILED carry a
 * {@code scheduledAt} in the future; a sweeper promotes them to PENDING once that time passes.
 *
 * <p>The diagram is the whole transition table: nothing else is legal. The only way into DEAD is
 * from RUNNING, since only an attempt can exhaust the retry budget or fail permanently. A job
 * that has not started is removed by cancelling it, which deletes the row rather than moving it
 * to any state.
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
     * revives it.
     */
    DEAD;

    private static final Map<JobState, Set<JobState>> ALLOWED;

    static {
        // Only the moves the engine makes. A transition with no caller is a bug the check would
        // let through, so add one here together with the code path that needs it.
        Map<JobState, Set<JobState>> allowed = new EnumMap<>(JobState.class);
        // Claimed by a worker. Cancelling deletes the row; it is not a transition.
        allowed.put(PENDING, EnumSet.of(RUNNING));
        // Its delay elapsed (sweeper). Cancelling deletes the row, as for PENDING.
        allowed.put(SCHEDULED, EnumSet.of(PENDING));
        // Succeeded / failed with retries left / failed for good, or lost its lease on the last
        // permitted attempt / lost its lease with a retry left.
        allowed.put(RUNNING, EnumSet.of(COMPLETED, FAILED, DEAD, PENDING));
        // Backoff elapsed (sweeper). It had a retry left when it failed, so it never dead-letters.
        allowed.put(FAILED, EnumSet.of(PENDING));
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

    /** The states a manual retry accepts: DEAD alone. The single source for revive refusals. */
    public static Set<JobState> revivableStates() {
        return REVIVABLE;
    }

    private static final Set<JobState> CANCELLABLE = Arrays.stream(values())
            .filter(JobState::isCancellable)
            .collect(Collectors.toUnmodifiableSet());

    private static final Set<JobState> REVIVABLE = Set.of(DEAD);

    /** A mutable count map with every state present at zero, so absent states read as 0, not null. */
    public static Map<JobState, Long> zeroCounts() {
        Map<JobState, Long> counts = new EnumMap<>(JobState.class);
        for (JobState state : values()) {
            counts.put(state, 0L);
        }
        return counts;
    }
}
