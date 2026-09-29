package dev.dispatch.core.job;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * What a caller supplies when enqueuing work. The engine owns the rest of {@link Job}: its id,
 * state, timestamps, and attempt counter.
 *
 * @param type        handler routing key
 * @param payload     JSON document passed through verbatim to the handler
 * @param priority    higher runs first; 0 is the normal band
 * @param maxRetries  retries allowed beyond the first attempt
 * @param scheduledAt earliest execution time, or null for "as soon as possible"; must lie between
 *                    {@link #EARLIEST_SCHEDULED_AT} and {@link #LATEST_SCHEDULED_AT}
 */
public record JobSubmission(
        String type,
        String payload,
        int priority,
        int maxRetries,
        Instant scheduledAt) {

    public static final int DEFAULT_PRIORITY = 0;
    public static final int DEFAULT_MAX_RETRIES = 3;

    /**
     * The earliest {@code scheduledAt} accepted: the first instant of year 0001.
     *
     * <p>The bounds are the four-digit years, which every supported store and every ISO-8601
     * client can represent. Outside them PostgreSQL silently turns anything before 4713 BC into
     * {@code -infinity} (which sorts first, cannot be read back, and so fails every claim that
     * reaches it) and rejects anything after year 294276, so an unbounded value is not merely
     * unrepresentable but able to stall the whole queue.
     */
    public static final Instant EARLIEST_SCHEDULED_AT = Instant.parse("0001-01-01T00:00:00Z");

    /** The latest {@code scheduledAt} accepted: the last instant of year 9999. */
    public static final Instant LATEST_SCHEDULED_AT = Instant.parse("9999-12-31T23:59:59.999999999Z");

    public JobSubmission {
        Objects.requireNonNull(type, "type");
        if (type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative: " + maxRetries);
        }
        if (scheduledAt != null && (scheduledAt.isBefore(EARLIEST_SCHEDULED_AT)
                || scheduledAt.isAfter(LATEST_SCHEDULED_AT))) {
            throw new IllegalArgumentException("scheduledAt must be between "
                    + EARLIEST_SCHEDULED_AT + " and " + LATEST_SCHEDULED_AT + ": " + scheduledAt);
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
