package dev.dispatch.api.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything under {@code dispatch.*} in application.yml.
 *
 * <p>The engine knobs and retry settings are deliberately nullable: null means "not configured",
 * and the engine's own types treat null as "keep my default" ({@code QueueConfig.Builder},
 * {@code ExponentialBackoffRetryPolicy.of}). Keeping a second copy of the numbers here would let
 * the two silently drift apart.
 *
 * @param store              which {@code JobStore} to wire up
 * @param workerId           this instance's identity in {@code locked_by}; leave blank to
 *                           auto-generate a unique one, which is what you want in containers
 * @param concurrency        maximum jobs in flight on this instance
 * @param claimBatchSize     jobs claimed per database round trip
 * @param pollInterval       idle dispatcher poll interval
 * @param visibilityTimeout  how long a claim stays exclusive before the job is deemed abandoned
 * @param maintenanceInterval how often to promote due jobs and reclaim expired leases
 * @param maintenanceBatchSize row cap per maintenance pass
 * @param shutdownDrainTimeout how long shutdown waits for in-flight jobs
 * @param retry              backoff settings
 * @param demoHandlers       register the bundled send-email and resize-image simulators
 */
@ConfigurationProperties("dispatch")
public record QueueProperties(
        @DefaultValue("JDBC") StoreType store,
        @DefaultValue("") String workerId,
        Integer concurrency,
        Integer claimBatchSize,
        Duration pollInterval,
        Duration visibilityTimeout,
        Duration maintenanceInterval,
        Integer maintenanceBatchSize,
        Duration shutdownDrainTimeout,
        @DefaultValue Retry retry,
        @DefaultValue("true") boolean demoHandlers) {

    /** Which {@code JobStore} implementation to use. */
    public enum StoreType {
        /** Shared, durable, safe across instances. The real one. */
        JDBC,
        /** Process-local and lost on restart. Handy for demos and for seeing the seam work. */
        MEMORY
    }

    /**
     * @param baseDelay    delay before the first retry
     * @param multiplier   growth factor per attempt
     * @param maxDelay     ceiling the growth is clamped to
     * @param jitterFactor 0 for none, 1 for full jitter, 0.5 keeps at least half the nominal delay
     */
    public record Retry(
            Duration baseDelay,
            Double multiplier,
            Duration maxDelay,
            Double jitterFactor) {
    }
}
