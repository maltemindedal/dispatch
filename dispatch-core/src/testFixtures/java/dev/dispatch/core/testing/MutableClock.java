package dev.dispatch.core.testing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A clock the test drives by hand, so scheduling and timeout behaviour can be tested by moving
 * time rather than by sleeping through it. Sleeping tests are slow and flaky; this is neither.
 *
 * <p>Instants are truncated to milliseconds because they have to survive a round trip through a
 * database column — PostgreSQL keeps microseconds, not nanoseconds — and the shared store contract
 * compares them for equality.
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    private MutableClock(Instant start, ZoneId zone) {
        this.now = new AtomicReference<>(start.truncatedTo(ChronoUnit.MILLIS));
        this.zone = zone;
    }

    /** A fixed, arbitrary starting point, so failures read the same on every machine. */
    public static MutableClock atEpoch() {
        return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(now.get(), newZone);
    }

    public MutableClock advance(Duration amount) {
        now.updateAndGet(current -> current.plus(amount).truncatedTo(ChronoUnit.MILLIS));
        return this;
    }

    public MutableClock advanceSeconds(long seconds) {
        return advance(Duration.ofSeconds(seconds));
    }

    public MutableClock setTo(Instant instant) {
        now.set(instant.truncatedTo(ChronoUnit.MILLIS));
        return this;
    }
}
