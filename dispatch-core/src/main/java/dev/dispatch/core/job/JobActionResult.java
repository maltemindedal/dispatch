package dev.dispatch.core.job;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The engine's answer to an operator action on one job — cancel, manual retry.
 *
 * <p>The point of returning this rather than a boolean is that a refusal carries the state the
 * store actually observed, read in the same atomic step that refused the action. A caller can
 * report "job X is RUNNING; this needs one of [PENDING, SCHEDULED]" without a second read — which
 * could observe a different state — and without knowing the rule itself.
 */
public sealed interface JobActionResult {

    /**
     * The action happened. For a revive this is the resulting snapshot; for a cancel it is the
     * final snapshot of the row that was removed.
     */
    record Done(Job job) implements JobActionResult {
        public Done {
            Objects.requireNonNull(job, "job");
        }
    }

    /** No job with this id exists. */
    record NotFound(UUID id) implements JobActionResult {
        public NotFound {
            Objects.requireNonNull(id, "id");
        }
    }

    /** The job exists, but the state it was observed in does not allow the action. */
    record WrongState(Job observed, Set<JobState> allowedStates) implements JobActionResult {
        public WrongState {
            Objects.requireNonNull(observed, "observed");
            // EnumSet keeps declaration order, so refusal messages read in lifecycle order.
            allowedStates = Collections.unmodifiableSet(EnumSet.copyOf(allowedStates));
        }
    }
}
