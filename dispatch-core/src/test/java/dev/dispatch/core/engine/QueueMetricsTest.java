package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
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
        metrics.jobFinished(Duration.ofMillis(100));
        metrics.jobSucceeded();
        metrics.jobStarted();
        metrics.jobFinished(Duration.ofMillis(300));
        metrics.attemptFailed();

        assertThat(metrics.averageExecutionMillis()).isCloseTo(200.0, Offset.offset(1e-9));
    }

    @Test
    @DisplayName("a run whose result was never recorded still counts as a run in the average")
    void unrecordedRunsAreAveragedToo() {
        // The lease was lost after the handler finished, so neither succeeded nor failedAttempts
        // moved. Its 300 ms is in the total, so it must be in the count too.
        metrics.jobStarted();
        metrics.jobFinished(Duration.ofMillis(300));
        metrics.jobStarted();
        metrics.jobFinished(Duration.ofMillis(100));
        metrics.jobSucceeded();

        assertThat(metrics.averageExecutionMillis()).isCloseTo(200.0, Offset.offset(1e-9));
        // The per-attempt counters keep their own meaning.
        assertThat(metrics.attemptsFinished()).isEqualTo(1);
    }

    @Test
    @DisplayName("runs shorter than a millisecond are not rounded down to nothing")
    void subMillisecondRunsKeepTheirLength() {
        metrics.jobStarted();
        metrics.jobFinished(Duration.ofNanos(400_000));
        metrics.jobSucceeded();
        metrics.jobStarted();
        metrics.jobFinished(Duration.ofNanos(600_000));
        metrics.jobSucceeded();

        assertThat(metrics.averageExecutionMillis()).isCloseTo(0.5, Offset.offset(1e-9));
    }

    @Test
    @DisplayName("the failure rate never exceeds 1 while failures are landing")
    void failureRateStaysWithinBoundsUnderConcurrentUpdates() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        Thread writer = new Thread(() -> {
            while (!stop.get()) {
                metrics.attemptFailed();
            }
        });
        writer.start();
        try {
            long deadline = System.nanoTime() + Duration.ofMillis(300).toNanos();
            double worst = 0.0;
            while (System.nanoTime() < deadline) {
                worst = Math.max(worst, metrics.failureRate());
            }
            // Only failures are being recorded, so the rate is 1.0 whenever it is read from a
            // consistent view. Reading the failures twice made it exceed 1.
            assertThat(worst).isLessThanOrEqualTo(1.0);
        } finally {
            stop.set(true);
            writer.join();
        }
    }

    @Test
    @DisplayName("inFlight rises on start and falls on finish")
    void inFlightTracksStartAndFinish() {
        metrics.jobStarted();
        metrics.jobStarted();
        assertThat(metrics.inFlight()).isEqualTo(2);

        metrics.jobFinished(Duration.ofMillis(1));
        assertThat(metrics.inFlight()).isEqualTo(1);
    }
}
