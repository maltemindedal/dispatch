package dev.dispatch.core.engine;

import java.util.concurrent.Semaphore;

/**
 * The worker pool's backpressure valve: a fixed pool of permits, one per job in flight.
 *
 * <p>The interface is a strict reserve/release pair. {@link #reserve} blocks until at least one
 * permit is free — this is what stops the dispatcher claiming work it has no room to run — then
 * opportunistically takes whatever else is free without waiting, capped at the claim batch size,
 * so a single claim query can fill a batch. The caller owes back exactly what {@code reserve}
 * returned: unused budget as soon as the claim comes up short, and one permit per dispatched job
 * when it finishes.
 *
 * <p>Invariant: permits are conserved. Every permit {@link #reserve} hands out comes back through
 * {@link #release}, so {@code available() + in-flight == concurrency} at every quiet point. The
 * whole point of this class is that the invariant lives — and is tested — in one place instead of
 * being arithmetic spread across the dispatch loop's happy, empty, failing, and shutting-down
 * paths.
 */
final class ClaimCapacity {

    private final Semaphore permits;

    ClaimCapacity(int concurrency) {
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be >= 1: " + concurrency);
        }
        this.permits = new Semaphore(concurrency);
    }

    /**
     * Blocks until at least one permit is free, then takes up to {@code maxBatch - 1} more
     * without waiting.
     *
     * @return the number of permits taken, in {@code [1, maxBatch]}; the caller must eventually
     *         {@link #release} exactly this many
     * @throws InterruptedException if interrupted while waiting; no permits are held in that case
     */
    int reserve(int maxBatch) throws InterruptedException {
        if (maxBatch < 1) {
            throw new IllegalArgumentException("maxBatch must be >= 1: " + maxBatch);
        }
        permits.acquire();
        int taken = 1;
        while (taken < maxBatch && permits.tryAcquire()) {
            taken++;
        }
        return taken;
    }

    /** Returns {@code n} permits taken by {@link #reserve}. Zero is fine; negative is a bug. */
    void release(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("cannot release a negative permit count: " + n);
        }
        if (n > 0) {
            permits.release(n);
        }
    }

    /** Free permits right now; equals the configured concurrency when nothing is in flight. */
    int available() {
        return permits.availablePermits();
    }
}
