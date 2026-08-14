package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The documented invariant: rates and means are per attempt, not per job. */
@DisplayName("Queue metrics")
class QueueMetricsTest {

    private final QueueMetrics metrics = new QueueMetrics();

    @Test
    @DisplayName("failure rate is per attempt: two failures then a success is 2/3")
    void failureRateCountsAttempts() {
        metrics.attemptFailed();
        metrics.attemptFailed();
        metrics.jobSucceeded();

        assertThat(metrics.attemptsFinished()).isEqualTo(3);
        assertThat(metrics.failureRate()).isCloseTo(2.0 / 3.0, Offset.offset(1e-9));
    }

    @Test
    @DisplayName("rates read as zero before anything has finished")
    void zeroBeforeAnyAttempts() {
        assertThat(metrics.failureRate()).isZero();
        assertThat(metrics.averageExecutionMillis()).isZero();
    }

    @Test
    @DisplayName("average execution time is the mean over finished attempts")
    void averageIsPerAttempt() {
        metrics.jobStarted();
        metrics.jobFinished(100);
        metrics.jobSucceeded();
        metrics.jobStarted();
        metrics.jobFinished(300);
        metrics.attemptFailed();

        assertThat(metrics.averageExecutionMillis()).isCloseTo(200.0, Offset.offset(1e-9));
    }

    @Test
    @DisplayName("inFlight rises on start and falls on finish")
    void inFlightTracksStartAndFinish() {
        metrics.jobStarted();
        metrics.jobStarted();
        assertThat(metrics.inFlight()).isEqualTo(2);

        metrics.jobFinished(1);
        assertThat(metrics.inFlight()).isEqualTo(1);
    }
}
