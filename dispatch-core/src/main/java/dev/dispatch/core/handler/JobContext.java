package dev.dispatch.core.handler;

import dev.dispatch.core.job.Job;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What a handler sees while it runs: the job itself, the worker executing it, and the visibility
 * deadline by which it should be done.
 */
public final class JobContext {

    private final Job job;
    private final String workerId;

    public JobContext(Job job, String workerId) {
        this.job = Objects.requireNonNull(job, "job");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
    }

    public Job job() {
        return job;
    }

    public UUID jobId() {
        return job.id();
    }

    public String type() {
        return job.type();
    }

    /** Raw JSON exactly as submitted. The engine deliberately never parses it. */
    public String payload() {
        return job.payload();
    }

    /** 1-based: 1 on the first run, 2 on the first retry, and so on. */
    public int attempt() {
        return job.attempt();
    }

    public int maxRetries() {
        return job.maxRetries();
    }

    /** True when a failure here will dead-letter the job rather than schedule another retry. */
    public boolean isFinalAttempt() {
        return job.retriesExhausted();
    }

    public String workerId() {
        return workerId;
    }

    /**
     * When the visibility lease expires. Run past this and another worker may start the same job
     * concurrently, so long-running handlers should check {@link #leaseExpired(Instant)} and bail
     * out rather than plough on.
     */
    public Instant leaseExpiresAt() {
        return job.lockedUntil();
    }

    public boolean leaseExpired(Instant now) {
        return job.lockedUntil() != null && !job.lockedUntil().isAfter(now);
    }

    /**
     * Cooperative cancellation. Graceful shutdown interrupts in-flight handlers once the drain
     * deadline passes, so handlers doing tight non-blocking work should poll this.
     */
    public boolean isCancelled() {
        return Thread.currentThread().isInterrupted();
    }

    @Override
    public String toString() {
        return "JobContext[job=" + job.id() + ", type=" + job.type() + ", attempt=" + job.attempt()
                + ", worker=" + workerId + "]";
    }
}
