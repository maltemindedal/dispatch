package dev.dispatch.core.engine;

import dev.dispatch.core.job.JobState;
import java.util.Map;

/**
 * What {@code GET /stats} answers with.
 *
 * @param workerId            which instance produced the process-scoped numbers
 * @param depthByState        queue depth per state, read from shared storage (cluster-wide)
 * @param totalJobs           rows in the store, all states
 * @param submitted           jobs enqueued through this instance since start
 * @param claimed             jobs claimed by this instance since start
 * @param succeeded           attempts that completed successfully on this instance
 * @param failedAttempts      attempts that threw on this instance
 * @param retriesScheduled    failures this instance put back on the queue with a backoff
 * @param deadLettered        jobs this instance moved to DEAD
 * @param leasesReclaimed     abandoned leases this instance's sweeper returned to PENDING
 * @param leasesLost          results this instance could not record because it lost the lease
 * @param inFlight            jobs running on this instance right now
 * @param failureRate         failed attempts over finished attempts, in {@code [0, 1]}
 * @param averageExecutionMs  mean handler wall time on this instance
 */
public record QueueStats(
        String workerId,
        Map<JobState, Long> depthByState,
        long totalJobs,
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

    public QueueStats {
        depthByState = Map.copyOf(depthByState);
    }

    static QueueStats of(String workerId, Map<JobState, Long> depthByState, long totalJobs,
            QueueMetrics metrics) {
        Map<JobState, Long> depth = JobState.zeroCounts();
        depth.putAll(depthByState);
        return new QueueStats(
                workerId,
                depth,
                totalJobs,
                metrics.submitted(),
                metrics.claimed(),
                metrics.succeeded(),
                metrics.failedAttempts(),
                metrics.retriesScheduled(),
                metrics.deadLettered(),
                metrics.leasesReclaimed(),
                metrics.leasesLost(),
                metrics.inFlight(),
                metrics.failureRate(),
                metrics.averageExecutionMillis());
    }

    public long depth(JobState state) {
        return depthByState.getOrDefault(state, 0L);
    }

    /** Jobs still owed execution: PENDING + SCHEDULED + FAILED (awaiting retry). */
    public long backlog() {
        return depth(JobState.PENDING) + depth(JobState.SCHEDULED) + depth(JobState.FAILED);
    }
}
