package dev.dispatch.core.engine;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Engine tuning knobs.
 *
 * @param workerId            identifies this instance in {@code locked_by}; must be unique per
 *                            process, since the whole lease mechanism is keyed on it
 * @param concurrency         maximum jobs in flight on this instance
 * @param claimBatchSize      how many jobs one claim round trip may take, to amortise the query
 * @param pollInterval        how long an idle dispatcher parks before polling again; a submission
 *                            on this instance unparks it early, so this only bounds the latency of
 *                            work that appeared elsewhere
 * @param visibilityTimeout   how long a claim is exclusive before the job is considered abandoned;
 *                            must comfortably exceed the slowest handler or healthy jobs get run
 *                            twice
 * @param maintenanceInterval how often to promote due jobs and reclaim expired leases
 * @param maintenanceBatchSize row cap per maintenance pass, to keep those statements short
 * @param shutdownDrainTimeout how long {@code close()} waits for in-flight jobs before interrupting
 */
public record QueueConfig(
        String workerId,
        int concurrency,
        int claimBatchSize,
        Duration pollInterval,
        Duration visibilityTimeout,
        Duration maintenanceInterval,
        int maintenanceBatchSize,
        Duration shutdownDrainTimeout) {

    public QueueConfig {
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(pollInterval, "pollInterval");
        Objects.requireNonNull(visibilityTimeout, "visibilityTimeout");
        Objects.requireNonNull(maintenanceInterval, "maintenanceInterval");
        Objects.requireNonNull(shutdownDrainTimeout, "shutdownDrainTimeout");
        if (workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be >= 1: " + concurrency);
        }
        if (claimBatchSize < 1) {
            throw new IllegalArgumentException("claimBatchSize must be >= 1: " + claimBatchSize);
        }
        if (maintenanceBatchSize < 1) {
            throw new IllegalArgumentException("maintenanceBatchSize must be >= 1: " + maintenanceBatchSize);
        }
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive: " + pollInterval);
        }
        if (visibilityTimeout.isNegative() || visibilityTimeout.isZero()) {
            throw new IllegalArgumentException("visibilityTimeout must be positive: " + visibilityTimeout);
        }
        if (maintenanceInterval.isNegative() || maintenanceInterval.isZero()) {
            throw new IllegalArgumentException("maintenanceInterval must be positive: " + maintenanceInterval);
        }
        if (shutdownDrainTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdownDrainTimeout must not be negative: " + shutdownDrainTimeout);
        }
    }

    /** Reasonable defaults with a random worker id. */
    public static QueueConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Mutable builder; the record itself stays immutable.
     *
     * <p>Every setter treats {@code null} — and, for the worker id, blank — as "not configured":
     * the field keeps its default. That is what lets an adapter binding external configuration
     * hand its values straight over, and it puts the defaults in exactly one place, here.
     */
    public static final class Builder {
        private String workerId = generateWorkerId();
        private int concurrency = 16;
        private int claimBatchSize = 8;
        private Duration pollInterval = Duration.ofMillis(250);
        private Duration visibilityTimeout = Duration.ofMinutes(5);
        private Duration maintenanceInterval = Duration.ofSeconds(1);
        private int maintenanceBatchSize = 500;
        private Duration shutdownDrainTimeout = Duration.ofSeconds(30);

        /** Null or blank keeps the generated id — replicas must never share one. */
        public Builder workerId(String value) {
            if (value != null && !value.isBlank()) {
                this.workerId = value;
            }
            return this;
        }

        public Builder concurrency(Integer value) {
            if (value != null) {
                this.concurrency = value;
            }
            return this;
        }

        public Builder claimBatchSize(Integer value) {
            if (value != null) {
                this.claimBatchSize = value;
            }
            return this;
        }

        public Builder pollInterval(Duration value) {
            if (value != null) {
                this.pollInterval = value;
            }
            return this;
        }

        public Builder visibilityTimeout(Duration value) {
            if (value != null) {
                this.visibilityTimeout = value;
            }
            return this;
        }

        public Builder maintenanceInterval(Duration value) {
            if (value != null) {
                this.maintenanceInterval = value;
            }
            return this;
        }

        public Builder maintenanceBatchSize(Integer value) {
            if (value != null) {
                this.maintenanceBatchSize = value;
            }
            return this;
        }

        public Builder shutdownDrainTimeout(Duration value) {
            if (value != null) {
                this.shutdownDrainTimeout = value;
            }
            return this;
        }

        public QueueConfig build() {
            return new QueueConfig(workerId, concurrency, claimBatchSize, pollInterval,
                    visibilityTimeout, maintenanceInterval, maintenanceBatchSize, shutdownDrainTimeout);
        }
    }

    /**
     * Hostname-ish plus a random suffix. The suffix matters: two containers from the same image
     * would otherwise share a worker id and could steal each other's leases. This is the one
     * place worker ids are minted — anything wiring the engine up should call it rather than
     * invent its own scheme.
     */
    public static String generateWorkerId() {
        String host = System.getenv().getOrDefault("HOSTNAME", "worker");
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
