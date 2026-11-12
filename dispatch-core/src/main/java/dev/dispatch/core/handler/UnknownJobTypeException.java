package dev.dispatch.core.handler;

import java.util.Set;
import java.util.TreeSet;

/** No handler is registered for a job's type. */
public class UnknownJobTypeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnknownJobTypeException(String type, Set<String> knownTypes) {
        super("No handler registered for job type '" + type + "'; known types: "
                + new TreeSet<>(knownTypes));
    }
}
