package dev.dispatch.core.engine;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A daemon platform thread that runs one cycle over and over. It is the one place the engine
 * decides what a background thread does with a failure. Any {@link Throwable} from a cycle,
 * {@link Error} included, is logged and the loop parks one interval before the next cycle. If it
 * ended the thread, the queue would go on reporting itself running while nothing was claimed or
 * swept again. An {@link InterruptedException}, or {@link #stop}, ends the loop.
 */
final class BackgroundLoop {

    private static final Logger log = LoggerFactory.getLogger(BackgroundLoop.class);

    /** One pass of the loop. */
    @FunctionalInterface
    interface Cycle {
        /** @return true when there was work, so the next cycle runs at once without parking */
        boolean run() throws InterruptedException;
    }

    private final Thread thread;
    private volatile boolean running = true;

    /**
     * Builds the loop without starting its thread, so the owner can publish the loop to the
     * threads that wake or stop it before the first cycle runs.
     *
     * @param initialDelay how long to park before the first cycle
     * @param interval how long to park after a cycle that found no work, or that failed
     */
    BackgroundLoop(String threadName, Duration initialDelay, Duration interval, Cycle cycle) {
        // Converted once, and outside the loop: Duration.toNanos() throws for an interval beyond
        // about 292 years, and a throw from the error handler would end the thread for good.
        long initialDelayNanos = nanosSaturating(initialDelay);
        long intervalNanos = nanosSaturating(interval);
        thread = new Thread(() -> loop(cycle, initialDelayNanos, intervalNanos), threadName);
        thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    private void loop(Cycle cycle, long initialDelayNanos, long intervalNanos) {
        log.debug("{} running", thread.getName());
        // The interrupt of a stop() that came before start() may be lost, so ask the flag too.
        if (running) {
            LockSupport.parkNanos(this, initialDelayNanos);
        }
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                if (!cycle.run()) {
                    LockSupport.parkNanos(this, intervalNanos);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                // Never spin on a failing store: back off one interval and try again.
                log.error("{} hit an unexpected error; backing off", thread.getName(), t);
                LockSupport.parkNanos(this, intervalNanos);
            }
        }
        log.debug("{} stopped", thread.getName());
    }

    /** Ends a park early, so new work starts without waiting out the interval. */
    void wake() {
        LockSupport.unpark(thread);
    }

    /**
     * Ends the loop and interrupts a blocked or parked cycle. It does not wait for the thread.
     * A loop stopped before {@link #start} runs no cycle. Idempotent.
     */
    void stop() {
        running = false;
        thread.interrupt();
    }

    /** Waits up to {@code timeout} for a stopped loop's thread to end. */
    void awaitStopped(Duration timeout) {
        try {
            thread.join(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** {@code duration} in nanoseconds, or as many as a {@code long} holds if it is longer. */
    private static long nanosSaturating(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException tooLong) {
            return Long.MAX_VALUE;
        }
    }
}
