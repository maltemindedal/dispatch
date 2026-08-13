package dev.dispatch.core.job;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * What a caller supplies when enqueuing work. Everything else on {@link Job} — id, state,
 * timestamps, attempt counter — is the engine's business.
 *
 * @param type        handler routing key
 * @param payload     JSON document passed through verbatim to the handler
 * @param priority    higher runs first; 0 is the normal band
 * @param maxRetries  retries allowed beyond the first attempt
 * @param scheduledAt earliest execution time, or null for "as soon as possible"
 */
public record JobSubmission(
        String type,
        String payload,
        int priority,
        int maxRetries,
        Instant scheduledAt) {

    public static final int DEFAULT_PRIORITY = 0;
    public static final int DEFAULT_MAX_RETRIES = 3;

    public JobSubmission {
        Objects.requireNonNull(type, "type");
        if (type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative: " + maxRetries);
        }
    }

    /** Run now, default priority and retry budget. */
    public static JobSubmission of(String type, String payload) {
        return new JobSubmission(type, payload, DEFAULT_PRIORITY, DEFAULT_MAX_RETRIES, null);
    }

    /** Run no earlier than {@code delay} from now. */
    public static JobSubmission delayed(String type, String payload, Duration delay, Instant now) {
        return new JobSubmission(type, payload, DEFAULT_PRIORITY, DEFAULT_MAX_RETRIES, now.plus(delay));
    }
}
