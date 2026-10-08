package dev.dispatch.core.retry;

import java.time.Duration;

/** Decides how long to wait before re-running a job that just failed. */
@FunctionalInterface
public interface RetryPolicy {

    /**
     * Asked after every failed attempt, the last permitted one included. The store decides whether
     * a retry is left, and on the last attempt it dead-letters the job and ignores the answer. So a
     * policy must answer for any attempt from 1 up, not only for attempts that will be retried; one
     * that throws leaves the failure unrecorded until the lease expires.
     *
     * @param attempt the attempt that just failed, 1-based
     * @return how long to wait before the next attempt
     */
    Duration backoffAfter(int attempt);

    /** No waiting at all; handy in tests where you want the retry to land immediately. */
    static RetryPolicy immediate() {
        return attempt -> Duration.ZERO;
    }

    /** A flat delay between every attempt. */
    static RetryPolicy fixed(Duration delay) {
        return attempt -> delay;
    }
}
