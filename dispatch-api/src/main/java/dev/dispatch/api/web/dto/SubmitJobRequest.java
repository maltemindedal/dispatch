package dev.dispatch.api.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Body of {@code POST /jobs}.
 *
 * <pre>{@code
 * {
 *   "type": "send-email",
 *   "payload": { "to": "someone@example.com", "subject": "Hello" },
 *   "priority": 10,
 *   "maxRetries": 5,
 *   "scheduledAt": "2026-01-01T09:00:00Z"
 * }
 * }</pre>
 *
 * @param payload     arbitrary JSON, stored verbatim and handed to the handler untouched
 * @param priority    higher runs first; omit for the normal band
 * @param maxRetries  retries beyond the first attempt; omit for 3
 * @param scheduledAt run no earlier than this instant; omit to run as soon as possible
 */
public record SubmitJobRequest(
        @NotBlank(message = "type is required")
        @Size(max = 255, message = "type must be at most 255 characters")
        String type,

        JsonNode payload,

        Integer priority,

        @Min(value = 0, message = "maxRetries must not be negative")
        Integer maxRetries,

        Instant scheduledAt) {
}
