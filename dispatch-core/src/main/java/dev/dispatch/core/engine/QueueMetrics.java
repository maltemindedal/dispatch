package dev.dispatch.core.engine;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counters for what this process has done since it started.
 *
 * <p>Scope matters when reading these: they are per-instance and reset on restart, unlike the
 * queue depths in {@link dev.dispatch.core.store.JobStore#countsByState()}, which come from shared
 * storage and describe the whole cluster. {@link QueueStats} carries both and labels which is which.
 */
public final class QueueMetrics {

    private final LongAdder submitted = new LongAdder();
    private final LongAdder claimed = new LongAdder();
    private final LongAdder succeeded = new LongAdder();
    private final LongAdder failedAttempts = new LongAdder();
    private final LongAdder retriesScheduled = new LongAdder();
    private final LongAdder deadLettered = new LongAdder();
    private final LongAdder leasesReclaimed = new LongAdder();
    private final LongAdder leasesLost = new LongAdder();
    private final LongAdder executionNanos = new LongAdder();
    private final LongAdder runsFinished = new LongAdder();
    private final AtomicInteger inFlight = new AtomicInteger();

    void jobSubmitted() {
        submitted.increment();
    }

    void jobsClaimed(int count) {
        claimed.add(count);
    }

    void jobStarted() {
        inFlight.incrementAndGet();
    }

    /**
     * A run of the handler ended, however it ended. Time and count go in together, so the mean
     * over them stays a mean even for runs whose result could not be recorded (a lost lease).
     */
    void jobFinished(Duration elapsed) {
        inFlight.decrementAndGet();
        executionNanos.add(elapsed.toNanos());
        runsFinished.increment();
    }

    void jobSucceeded() {
        succeeded.increment();
    }

    void attemptFailed() {
        failedAttempts.increment();
    }

    void retryScheduled() {
        retriesScheduled.increment();
    }

    void jobDeadLettered() {
        deadLettered.increment();
    }

    void leasesReclaimed(int count) {
        leasesReclaimed.add(count);
    }

    /** A result could not be recorded because another worker had taken the job over. */
    void leaseLost() {
        leasesLost.increment();
    }

    public long submitted() {
        return submitted.sum();
    }

    public long claimed() {
        return claimed.sum();
    }

    public long succeeded() {
        return succeeded.sum();
    }

    public long failedAttempts() {
        return failedAttempts.sum();
    }

    public long retriesScheduled() {
        return retriesScheduled.sum();
    }

    public long deadLettered() {
        return deadLettered.sum();
    }

    public long leasesReclaimed() {
        return leasesReclaimed.sum();
    }

    public long leasesLost() {
        return leasesLost.sum();
    }

    public int inFlight() {
        return inFlight.get();
    }

    /** Attempts that finished, successfully or not. A retried job counts once per attempt. */
    public long attemptsFinished() {
        return succeeded.sum() + failedAttempts.sum();
    }

    /**
     * Share of finished attempts that threw, in {@code [0, 1]}. Per attempt, not per job: a job
     * that fails twice and then succeeds contributes two failures and one success.
     */
    public double failureRate() {
        // Each counter is read once. Reading the failures again after computing the total let a
        // failure that landed in between push the rate above 1.
        long failed = failedAttempts.sum();
        long total = succeeded.sum() + failed;
        return total == 0 ? 0.0 : (double) failed / total;
    }

    /**
     * Mean wall time of a run: from the start of the attempt until its outcome was recorded (or
     * failed to be), so it includes the store round trip and not just the handler. Averaged over
     * every run that finished, whether or not its result was recorded.
     */
    public double averageExecutionMillis() {
        long runs = runsFinished.sum();
        return runs == 0 ? 0.0 : executionNanos.sum() / 1_000_000.0 / runs;
    }
}
