package dev.dispatch.core.engine;

import dev.dispatch.core.job.JobState;
import java.util.Map;

/**
 * What {@code GET /stats} answers with: cluster-wide queue depth next to this instance's own
 * counters, labelled so the two scopes cannot be confused.
 *
 * <p>The depth map is a single {@link dev.dispatch.core.store.JobStore#countsByState()} read, so
 * the derived numbers — {@link #totalJobs()}, {@link #backlog()} — are sums over one snapshot and
 * always agree with it, rather than coming from separate round trips.
 *
 * @param workerId     which instance produced the process-scoped numbers
 * @param depthByState queue depth per state, read from shared storage (cluster-wide)
 * @param instance     counters for what this process has done since it started
 */
public record QueueStats(
        String workerId,
        Map<JobState, Long> depthByState,
        QueueMetrics instance) {

    public QueueStats {
        depthByState = Map.copyOf(depthByState);
    }

    public long depth(JobState state) {
        return depthByState.getOrDefault(state, 0L);
    }

    /** Rows in the store, all states: the sum of the depth snapshot. */
    public long totalJobs() {
        return depthByState.values().stream().mapToLong(Long::longValue).sum();
    }

    /** Jobs still owed execution: PENDING + SCHEDULED + FAILED (awaiting retry). */
    public long backlog() {
        return depth(JobState.PENDING) + depth(JobState.SCHEDULED) + depth(JobState.FAILED);
    }
}
