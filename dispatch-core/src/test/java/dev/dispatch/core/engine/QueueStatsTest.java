package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dispatch.core.job.JobState;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The derived numbers are sums over the one depth snapshot, so they can never disagree with it.
 */
@DisplayName("Queue stats")
class QueueStatsTest {

    private final QueueMetrics metrics = new QueueMetrics();

    @Test
    @DisplayName("totalJobs and backlog are derived from the same depth snapshot")
    void derivedNumbersAgreeWithTheSnapshot() {
        QueueStats stats = new QueueStats("w-1", Map.of(
                JobState.PENDING, 3L,
                JobState.SCHEDULED, 2L,
                JobState.RUNNING, 1L,
                JobState.COMPLETED, 10L,
                JobState.FAILED, 4L,
                JobState.DEAD, 1L), metrics);

        assertThat(stats.totalJobs()).isEqualTo(21);
        assertThat(stats.backlog()).isEqualTo(3 + 2 + 4);
        assertThat(stats.depth(JobState.RUNNING)).isEqualTo(1);
    }

    @Test
    @DisplayName("a state missing from the map counts as zero")
    void missingStatesCountAsZero() {
        QueueStats stats = new QueueStats("w-1", Map.of(JobState.PENDING, 2L), metrics);

        assertThat(stats.depth(JobState.DEAD)).isZero();
        assertThat(stats.totalJobs()).isEqualTo(2);
        assertThat(stats.backlog()).isEqualTo(2);
    }

    @Test
    @DisplayName("the depth snapshot is defensively copied")
    void depthSnapshotIsImmutable() {
        QueueStats stats = new QueueStats("w-1", Map.of(JobState.PENDING, 1L), metrics);

        assertThatThrownBy(() -> stats.depthByState().put(JobState.PENDING, 99L))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
