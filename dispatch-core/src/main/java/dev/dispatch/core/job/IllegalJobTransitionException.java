package dev.dispatch.core.job;

/** Thrown when code attempts a state change the job lifecycle does not permit. */
public class IllegalJobTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Transition transition;
    private final JobState from;

    public IllegalJobTransitionException(Transition transition, JobState from) {
        super("Illegal job transition " + transition + " from " + from + " -> " + transition.to()
                + "; " + transition + " moves only " + transition.from() + "; allowed from " + from
                + ": " + from.allowedTransitions());
        this.transition = transition;
        this.from = from;
    }

    public Transition transition() {
        return transition;
    }

    public JobState from() {
        return from;
    }

    public JobState to() {
        return transition.to();
    }
}
