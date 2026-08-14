package dev.dispatch.core.retry;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with jitter.
 *
 * <p>The undithered delay for attempt <em>n</em> is {@code base * multiplier^(n-1)}, clamped to
 * {@code maxDelay}. Jitter then spreads it randomly downward:
 *
 * <pre>{@code delay = ceiling * (1 - jitterFactor + jitterFactor * random[0,1))}</pre>
 *
 * <p>So {@code jitterFactor = 0} is pure exponential backoff, {@code 1.0} is AWS-style "full
 * jitter" (uniform over {@code [0, ceiling)}), and the default {@code 0.5} keeps at least half the
 * nominal delay while still breaking up convoys.
 *
 * <p>Jitter is the point, not a decoration: without it, a batch of jobs that fail together —
 * because the same downstream service was down — retries in lockstep forever, and every retry
 * wave hits that service simultaneously.
 */
public final class ExponentialBackoffRetryPolicy implements RetryPolicy {

    private static final Duration DEFAULT_BASE_DELAY = Duration.ofSeconds(1);
    private static final double DEFAULT_MULTIPLIER = 2.0;
    private static final Duration DEFAULT_MAX_DELAY = Duration.ofMinutes(1);
    private static final double DEFAULT_JITTER_FACTOR = 0.5;

    private final Duration baseDelay;
    private final double multiplier;
    private final Duration maxDelay;
    private final double jitterFactor;
    private final Supplier<RandomGenerator> random;

    public ExponentialBackoffRetryPolicy(
            Duration baseDelay, double multiplier, Duration maxDelay, double jitterFactor) {
        this(baseDelay, multiplier, maxDelay, jitterFactor, ThreadLocalRandom::current);
    }

    /** Overload taking the RNG, so tests can pin the jitter. */
    public ExponentialBackoffRetryPolicy(
            Duration baseDelay,
            double multiplier,
            Duration maxDelay,
            double jitterFactor,
            Supplier<RandomGenerator> random) {
        this.baseDelay = Objects.requireNonNull(baseDelay, "baseDelay");
        this.maxDelay = Objects.requireNonNull(maxDelay, "maxDelay");
        this.random = Objects.requireNonNull(random, "random");
        if (baseDelay.isNegative()) {
            throw new IllegalArgumentException("baseDelay must not be negative: " + baseDelay);
        }
        if (maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException(
                    "maxDelay (" + maxDelay + ") must be >= baseDelay (" + baseDelay + ")");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1.0: " + multiplier);
        }
        if (jitterFactor < 0.0 || jitterFactor > 1.0) {
            throw new IllegalArgumentException("jitterFactor must be within [0, 1]: " + jitterFactor);
        }
        this.multiplier = multiplier;
        this.jitterFactor = jitterFactor;
    }

    /** 1s base, doubling, capped at 1 minute, 50% jitter. Sensible for a demo queue. */
    public static ExponentialBackoffRetryPolicy defaults() {
        return of(null, null, null, null);
    }

    /**
     * Null-tolerant factory for adapters binding external configuration: any null falls back to
     * this policy's default, so the curve's numbers live here and nowhere else.
     */
    public static ExponentialBackoffRetryPolicy of(
            Duration baseDelay, Double multiplier, Duration maxDelay, Double jitterFactor) {
        return new ExponentialBackoffRetryPolicy(
                baseDelay != null ? baseDelay : DEFAULT_BASE_DELAY,
                multiplier != null ? multiplier : DEFAULT_MULTIPLIER,
                maxDelay != null ? maxDelay : DEFAULT_MAX_DELAY,
                jitterFactor != null ? jitterFactor : DEFAULT_JITTER_FACTOR);
    }

    @Override
    public Duration backoffAfter(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1: " + attempt);
        }
        long ceilingMillis = ceilingMillis(attempt);
        if (ceilingMillis == 0L) {
            return Duration.ZERO;
        }
        double factor = 1.0 - jitterFactor + jitterFactor * random.get().nextDouble();
        return Duration.ofMillis(Math.max(0L, Math.round(ceilingMillis * factor)));
    }

    /** The undithered delay for this attempt: what jitter is applied to. Exposed for tests. */
    public Duration ceilingFor(int attempt) {
        return Duration.ofMillis(ceilingMillis(attempt));
    }

    private long ceilingMillis(int attempt) {
        // Computed in double to sidestep overflow; the clamp brings it back into range long before
        // precision matters.
        double growth = Math.pow(multiplier, attempt - 1.0);
        double millis = baseDelay.toMillis() * growth;
        long capped = maxDelay.toMillis();
        if (Double.isInfinite(millis) || millis > capped) {
            return capped;
        }
        return (long) millis;
    }

    @Override
    public String toString() {
        return "ExponentialBackoffRetryPolicy[base=" + baseDelay + ", multiplier=" + multiplier
                + ", max=" + maxDelay + ", jitter=" + jitterFactor + "]";
    }
}
