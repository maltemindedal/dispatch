package dev.dispatch.core.handler;

/**
 * Signals a failure that retrying cannot fix — a malformed payload, a deleted target, a rejected
 * credential. The engine skips the retry budget and dead-letters the job immediately.
 */
public class PermanentJobFailureException extends Exception {

    private static final long serialVersionUID = 1L;

    public PermanentJobFailureException(String message) {
        super(message);
    }

    public PermanentJobFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
