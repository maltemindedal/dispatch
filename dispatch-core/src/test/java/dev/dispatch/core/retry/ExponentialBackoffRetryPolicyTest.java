package dev.dispatch.core.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Exponential backoff with jitter")
class ExponentialBackoffRetryPolicyTest {

    /** A stand-in RNG returning a fixed value, so jitter becomes predictable. */
    private static RandomGenerator fixedRandom(double value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0L;
            }

            @Override
            public double nextDouble() {
                return value;
            }
        };
    }

    @Test
    @DisplayName("without jitter the delay doubles each attempt")
    void doublesWithoutJitter() {
        RetryPolicy policy = new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(1), 2.0, Duration.ofHours(1), 0.0);

        assertThat(policy.backoffAfter(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.backoffAfter(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.backoffAfter(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.backoffAfter(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.backoffAfter(5)).isEqualTo(Duration.ofSeconds(16));
    }

    @Test
    @DisplayName("growth is clamped at maxDelay instead of running away")
    void clampsAtMax() {
        RetryPolicy policy = new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10), 0.0);

        assertThat(policy.backoffAfter(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(policy.backoffAfter(5)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.backoffAfter(50)).isEqualTo(Duration.ofSeconds(10));
        // Far enough out that a naive long computation would have overflowed.
        assertThat(policy.backoffAfter(2_000)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("jitterFactor 0.5 keeps the delay within [50%, 100%] of the ceiling")
    void halfJitterBounds() {
        ExponentialBackoffRetryPolicy floorCase = new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(10), 2.0, Duration.ofHours(1), 0.5, () -> fixedRandom(0.0));
        ExponentialBackoffRetryPolicy ceilingCase = new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(10), 2.0, Duration.ofHours(1), 0.5,
                () -> fixedRandom(Math.nextDown(1.0)));

        assertThat(floorCase.backoffAfter(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(ceilingCase.backoffAfter(1)).isBetween(
                Duration.ofMillis(9_990), Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("jitterFactor 1.0 is full jitter: anywhere in [0, ceiling)")
    void fullJitterBounds() {
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(10), 2.0, Duration.ofHours(1), 1.0, () -> fixedRandom(0.0));

        assertThat(policy.backoffAfter(1)).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("real jitter spreads retries out instead of stacking them")
    void jitterProducesSpread() {
        RetryPolicy policy = new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(10), 2.0, Duration.ofHours(1), 0.5);

        List<Duration> delays = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            delays.add(policy.backoffAfter(3));
        }

        Duration ceiling = Duration.ofSeconds(40);
        assertThat(delays).allSatisfy(delay -> assertThat(delay)
                .isBetween(ceiling.dividedBy(2), ceiling));
        // The point of jitter: a batch of jobs failing together must not retry in lockstep.
        assertThat(delays.stream().distinct().count()).isGreaterThan(50);
    }

    @Test
    @DisplayName("the undithered ceiling is exposed for inspection")
    void ceilingIsVisible() {
        ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(
                Duration.ofMillis(500), 3.0, Duration.ofMinutes(1), 0.5);

        assertThat(policy.ceilingFor(1)).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.ceilingFor(2)).isEqualTo(Duration.ofMillis(1_500));
        assertThat(policy.ceilingFor(3)).isEqualTo(Duration.ofMillis(4_500));
    }

    @Test
    @DisplayName("the defaults are 1s doubling, capped at a minute")
    void defaults() {
        ExponentialBackoffRetryPolicy policy = ExponentialBackoffRetryPolicy.defaults();

        assertThat(policy.ceilingFor(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.ceilingFor(7)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.backoffAfter(1)).isBetween(Duration.ofMillis(500), Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("the simple policies behave as advertised")
    void simplePolicies() {
        assertThat(RetryPolicy.immediate().backoffAfter(9)).isEqualTo(Duration.ZERO);
        assertThat(RetryPolicy.fixed(Duration.ofSeconds(7)).backoffAfter(3))
                .isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    @DisplayName("nonsense configuration is rejected up front")
    void validatesConfiguration() {
        assertThatThrownBy(() -> new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(1), 0.5, Duration.ofMinutes(1), 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiplier");
        assertThatThrownBy(() -> new ExponentialBackoffRetryPolicy(
                Duration.ofSeconds(1), 2.0, Duration.ofMinutes(1), 1.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jitterFactor");
        assertThatThrownBy(() -> new ExponentialBackoffRetryPolicy(
                Duration.ofMinutes(5), 2.0, Duration.ofSeconds(1), 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDelay");
        assertThatThrownBy(() -> ExponentialBackoffRetryPolicy.defaults().backoffAfter(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempt");
    }
}
