package dev.dispatch.core.store;

/**
 * How a {@link JobStore} reports that storage itself failed — a lost connection, a broken schema —
 * as opposed to an answer like "not found", which the interface expresses as a return value.
 *
 * <p>Living here rather than in an adapter module makes the failure mode part of the interface:
 * every adapter wraps its own plumbing exceptions (SQL or otherwise) in this one type, and callers
 * can catch it without knowing which adapter sits behind the seam.
 */
public class JobStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JobStoreException(String message) {
        super(message);
    }

    public JobStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
