package dev.dispatch.core.handler;

import java.util.Optional;
import java.util.Set;

/** Maps a job type to the code that runs it. */
public interface JobHandlerRegistry {

    Optional<JobHandler> lookup(String type);

    /** Every registered type. Useful for a startup sanity check against queued job types. */
    Set<String> registeredTypes();

    default JobHandler require(String type) {
        return lookup(type).orElseThrow(() -> new UnknownJobTypeException(type, registeredTypes()));
    }
}
