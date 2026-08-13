package dev.dispatch.api.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import java.time.Instant;
import java.util.UUID;

/**
 * A job as the API presents it.
 *
 * <p>Mostly a mirror of {@link Job}, with two deliberate differences: the payload comes back as
 * real JSON rather than an escaped string, and {@code retriesRemaining} is computed so a caller
 * does not have to know the {@code maxRetries}/{@code attempt} arithmetic.
 */
public record JobResponse(
        UUID id,
        String type,
        JsonNode payload,
        int priority,
        int maxRetries,
        int attempt,
        int retriesRemaining,
        JobState state,
        Instant scheduledAt,
        Instant createdAt,
        Instant updatedAt,
        Instant lockedUntil,
        String lockedBy,
        String lastError) {

    public static JobResponse from(Job job, JsonNode payload) {
        return new JobResponse(
                job.id(),
                job.type(),
                payload,
                job.priority(),
                job.maxRetries(),
                job.attempt(),
                job.retriesRemaining(),
                job.state(),
                job.scheduledAt(),
                job.createdAt(),
                job.updatedAt(),
                job.lockedUntil(),
                job.lockedBy(),
                job.lastError());
    }
}
