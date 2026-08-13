package dev.dispatch.core.job;

/** Thrown when code attempts a state change the job lifecycle does not permit. */
public class IllegalJobTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final JobState from;
    private final JobState to;

    public IllegalJobTransitionException(JobState from, JobState to) {
        super("Illegal job transition " + from + " -> " + to + "; allowed from " + from + ": "
                + from.allowedTransitions());
        this.from = from;
        this.to = to;
    }

    public JobState from() {
        return from;
    }

    public JobState to() {
        return to;
    }
}
