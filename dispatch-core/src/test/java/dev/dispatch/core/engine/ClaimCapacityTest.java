package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The claim-capacity invariant: permits are conserved across every path the dispatch loop can
 * take — full batch, short claim, empty claim, store failure, shutdown race.
 */
@DisplayName("Claim capacity")
class ClaimCapacityTest {

    @Test
    @DisplayName("reserve is capped at the batch size even when more permits are free")
    void reserveCapsAtBatchSize() throws Exception {
        ClaimCapacity capacity = new ClaimCapacity(16);

        assertThat(capacity.reserve(8)).isEqualTo(8);
        assertThat(capacity.available()).isEqualTo(8);
    }

    @Test
    @DisplayName("reserve takes only what is free when the pool is nearly saturated")
    void reserveTakesWhatIsFree() throws Exception {
        ClaimCapacity capacity = new ClaimCapacity(4);

        assertThat(capacity.reserve(8)).isEqualTo(4);
        assertThat(capacity.available()).isZero();
    }

    @Test
    @DisplayName("full concurrency is reachable across consecutive reserves")
    void fullConcurrencyIsReachable() throws Exception {
        // Regression: the inline predecessor of this class drained every free permit but
        // returned at most claimBatchSize of them, so concurrency=16/batch=8 silently capped
        // in-flight work at 8.
        ClaimCapacity capacity = new ClaimCapacity(16);

        int inFlight = capacity.reserve(8) + capacity.reserve(8);

        assertThat(inFlight).isEqualTo(16);
        assertThat(capacity.available()).isZero();
    }

    @Test
    @DisplayName("permits are conserved across empty-claim, short-claim, and failure paths")
    void permitsAreConserved() throws Exception {
        ClaimCapacity capacity = new ClaimCapacity(16);

        // Empty claim: everything reserved goes straight back.
        int budget = capacity.reserve(8);
        capacity.release(budget);
        assertThat(capacity.available()).isEqualTo(16);

        // Short claim: 3 of 8 dispatched, 5 returned, then the 3 finish.
        budget = capacity.reserve(8);
        capacity.release(budget - 3);
        capacity.release(1);
        capacity.release(1);
        capacity.release(1);
        assertThat(capacity.available()).isEqualTo(16);

        // Store failure: the whole budget is returned in one go.
        budget = capacity.reserve(8);
        capacity.release(budget);
        assertThat(capacity.available()).isEqualTo(16);
    }

    @Test
    @DisplayName("reserve blocks when the pool is saturated and unblocks on release")
    void reserveBlocksWhenSaturated() throws Exception {
        ClaimCapacity capacity = new ClaimCapacity(1);
        assertThat(capacity.reserve(1)).isEqualTo(1);

        CountDownLatch reserved = new CountDownLatch(1);
        AtomicInteger taken = new AtomicInteger();
        Thread waiter = new Thread(() -> {
            try {
                taken.set(capacity.reserve(1));
                reserved.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();

        // The waiter cannot get a permit while one job is in flight.
        assertThat(reserved.await(100, TimeUnit.MILLISECONDS)).isFalse();

        capacity.release(1);
        assertThat(reserved.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(taken).hasValue(1);
        waiter.join(Duration.ofSeconds(5).toMillis());
    }

    @Test
    @DisplayName("misuse is rejected loudly")
    void misuseIsRejected() {
        assertThatThrownBy(() -> new ClaimCapacity(0))
                .isInstanceOf(IllegalArgumentException.class);
        ClaimCapacity capacity = new ClaimCapacity(1);
        assertThatThrownBy(() -> capacity.reserve(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> capacity.release(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
