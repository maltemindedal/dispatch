package dev.dispatch.api.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything under {@code dispatch.*} in application.yml.
 *
 * <p>Defaults live here rather than in the YAML so that a stripped configuration still boots into
 * something sensible.
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
        @DefaultValue("16") int concurrency,
        @DefaultValue("8") int claimBatchSize,
        @DefaultValue("250ms") Duration pollInterval,
        @DefaultValue("5m") Duration visibilityTimeout,
        @DefaultValue("1s") Duration maintenanceInterval,
        @DefaultValue("500") int maintenanceBatchSize,
        @DefaultValue("30s") Duration shutdownDrainTimeout,
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
            @DefaultValue("1s") Duration baseDelay,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("1m") Duration maxDelay,
            @DefaultValue("0.5") double jitterFactor) {
    }
}
