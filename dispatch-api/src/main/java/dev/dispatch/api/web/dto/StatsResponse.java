package dev.dispatch.api.web.dto;

import dev.dispatch.core.engine.QueueStats;
import dev.dispatch.core.job.JobState;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Body of {@code GET /stats}.
 *
 * <p>Split into two halves on purpose, because they answer different questions and mixing them
 * misleads: {@code queueDepth} comes from shared storage and describes the whole cluster right
 * now, while {@code thisInstance} counts what this process has done since it started and resets on
 * restart.
 *
 * @param workerId     which instance answered
 * @param queueDepth   jobs per state, across every instance
 * @param totalJobs    rows in the store
 * @param backlog      jobs still owed execution: PENDING + SCHEDULED + FAILED
 * @param thisInstance counters scoped to this process
 */
public record StatsResponse(
        String workerId,
        Map<String, Long> queueDepth,
        long totalJobs,
        long backlog,
        InstanceStats thisInstance) {

    /**
     * @param submitted          jobs enqueued here
     * @param claimed            jobs claimed here
     * @param succeeded          attempts that succeeded here
     * @param failedAttempts     attempts that threw here
     * @param retriesScheduled   failures put back on the queue with a backoff
     * @param deadLettered       jobs moved to DEAD
     * @param leasesReclaimed    abandoned leases this instance's sweeper recovered
     * @param leasesLost         results that could not be recorded because the lease was gone
     * @param inFlight           jobs running right now
     * @param failureRate        failed attempts over finished attempts, in [0, 1]
     * @param averageExecutionMs mean handler wall time
     */
    public record InstanceStats(
            long submitted,
            long claimed,
            long succeeded,
            long failedAttempts,
            long retriesScheduled,
            long deadLettered,
            long leasesReclaimed,
            long leasesLost,
            int inFlight,
            double failureRate,
            double averageExecutionMs) {
    }

    public static StatsResponse from(QueueStats stats) {
        // LinkedHashMap keyed by the enum's declaration order, so the JSON reads in lifecycle
        // order rather than alphabetically.
        Map<String, Long> depth = new LinkedHashMap<>();
        for (JobState state : JobState.values()) {
            depth.put(state.name(), stats.depth(state));
        }
        return new StatsResponse(
                stats.workerId(),
                depth,
                stats.totalJobs(),
                stats.backlog(),
                new InstanceStats(
                        stats.submitted(),
                        stats.claimed(),
                        stats.succeeded(),
                        stats.failedAttempts(),
                        stats.retriesScheduled(),
                        stats.deadLettered(),
                        stats.leasesReclaimed(),
                        stats.leasesLost(),
                        stats.inFlight(),
                        stats.failureRate(),
                        stats.averageExecutionMs()));
    }
}
