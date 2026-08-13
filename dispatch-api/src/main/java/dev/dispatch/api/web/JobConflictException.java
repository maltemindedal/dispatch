package dev.dispatch.api.web;

/**
 * The job exists, but its current state does not permit the requested action — cancelling a job a
 * worker is already running, or retrying one that is not dead. Mapped to 409.
 */
public class JobConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JobConflictException(String message) {
        super(message);
    }
}
