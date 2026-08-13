package dev.dispatch.core.demo;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** An inclusive range the demo handlers draw their simulated work times from. */
record DurationRange(Duration min, Duration max) {

    DurationRange {
        if (max.compareTo(min) < 0) {
            throw new IllegalArgumentException("max (" + max + ") must be >= min (" + min + ")");
        }
    }

    /** A uniformly random number of milliseconds within the range, both ends inclusive. */
    long randomMillis() {
        long minMillis = min.toMillis();
        long maxMillis = max.toMillis();
        return minMillis == maxMillis
                ? minMillis
                : ThreadLocalRandom.current().nextLong(minMillis, maxMillis + 1);
    }
}
