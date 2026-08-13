package dev.dispatch.core.retry;

import java.time.Duration;

/** Decides how long to wait before re-running a job that just failed. */
@FunctionalInterface
public interface RetryPolicy {

    /**
     * @param attempt the attempt that just failed, 1-based
     * @return how long to wait before the next attempt
     */
    Duration backoffAfter(int attempt);

    /** No waiting at all — handy in tests where you want the retry to land immediately. */
    static RetryPolicy immediate() {
        return attempt -> Duration.ZERO;
    }

    /** A flat delay between every attempt. */
    static RetryPolicy fixed(Duration delay) {
        return attempt -> delay;
    }
}
