package dev.dispatch.postgres;

/**
 * Wraps a {@link java.sql.SQLException} so the {@link dev.dispatch.core.store.JobStore} interface
 * stays free of checked JDBC exceptions and free of any hint of how a given store persists things.
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
