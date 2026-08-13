package dev.dispatch.core.handler;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The obvious registry: a concurrent map, safe to add to while the queue is running. */
public final class InMemoryJobHandlerRegistry implements JobHandlerRegistry {

    private final Map<String, JobHandler> handlers = new ConcurrentHashMap<>();

    /**
     * @throws IllegalStateException if the type is already taken — silently replacing a handler is
     *         the kind of thing you discover in production, so it is refused here
     */
    public InMemoryJobHandlerRegistry register(String type, JobHandler handler) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        JobHandler previous = handlers.putIfAbsent(type, handler);
        if (previous != null) {
            throw new IllegalStateException("A handler is already registered for job type: " + type);
        }
        return this;
    }

    /** Explicit override, for tests and hot-swapping. */
    public InMemoryJobHandlerRegistry replace(String type, JobHandler handler) {
        handlers.put(Objects.requireNonNull(type, "type"), Objects.requireNonNull(handler, "handler"));
        return this;
    }

    @Override
    public Optional<JobHandler> lookup(String type) {
        return Optional.ofNullable(handlers.get(type));
    }

    @Override
    public Set<String> registeredTypes() {
        return Set.copyOf(handlers.keySet());
    }
}
