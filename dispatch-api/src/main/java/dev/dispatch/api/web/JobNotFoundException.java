package dev.dispatch.api.web;

import java.util.UUID;

/** No job with that id. Mapped to 404 by {@link ApiExceptionHandler}. */
public class JobNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JobNotFoundException(UUID id) {
        super("No job with id " + id);
    }
}
