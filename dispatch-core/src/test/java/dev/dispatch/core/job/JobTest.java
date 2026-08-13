package dev.dispatch.core.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Job transitions and retry accounting")
class JobTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(5);

    private Job pendingJob(int maxRetries) {
        return Job.newJob(UUID.randomUUID(),
                new JobSubmission("send-email", "{}", 0, maxRetries, null), NOW);
    }

    @Test
    @DisplayName("a claim opens a lease, counts the attempt and names the holder")
    void claimOpensLease() {
        Job claimed = pendingJob(3).claimedBy("worker-1", NOW, LEASE);

        assertThat(claimed.state()).isEqualTo(JobState.RUNNING);
        assertThat(claimed.attempt()).isEqualTo(1);
        assertThat(claimed.lockedBy()).isEqualTo("worker-1");
        assertThat(claimed.lockedUntil()).isEqualTo(NOW.plus(LEASE));
    }

    @Test
    @DisplayName("maxRetries=2 means three attempts in total")
    void retryBudgetArithmetic() {
        Job job = pendingJob(2);
        assertThat(job.retriesRemaining()).isEqualTo(2);

        Job firstAttempt = job.claimedBy("w", NOW, LEASE);
        assertThat(firstAttempt.attempt()).isEqualTo(1);
        assertThat(firstAttempt.retriesRemaining()).isEqualTo(2);
        assertThat(firstAttempt.retriesExhausted()).isFalse();

        Job secondAttempt = firstAttempt
                .failedWithRetryAt(NOW, "boom", NOW)
                .promotedToPending(NOW)
                .claimedBy("w", NOW, LEASE);
        assertThat(secondAttempt.attempt()).isEqualTo(2);
        assertThat(secondAttempt.retriesRemaining()).isEqualTo(1);

        Job thirdAttempt = secondAttempt
                .failedWithRetryAt(NOW, "boom", NOW)
                .promotedToPending(NOW)
                .claimedBy("w", NOW, LEASE);
        assertThat(thirdAttempt.attempt()).isEqualTo(3);
        assertThat(thirdAttempt.retriesRemaining()).isZero();
        // Failing here dead-letters rather than retrying.
        assertThat(thirdAttempt.retriesExhausted()).isTrue();
    }

    @Test
    @DisplayName("maxRetries=0 means the first failure is fatal")
    void zeroRetriesDiesImmediately() {
        Job firstAttempt = pendingJob(0).claimedBy("w", NOW, LEASE);

        assertThat(firstAttempt.attempt()).isEqualTo(1);
        assertThat(firstAttempt.retriesExhausted()).isTrue();
    }

    @Test
    @DisplayName("completing releases the lease")
    void completeReleasesLease() {
        Job completed = pendingJob(3).claimedBy("w", NOW, LEASE).completed(NOW);

        assertThat(completed.state()).isEqualTo(JobState.COMPLETED);
        assertThat(completed.lockedBy()).isNull();
        assertThat(completed.lockedUntil()).isNull();
    }

    @Test
    @DisplayName("failing parks the job at its backoff time and records the error")
    void failStoresBackoffAndError() {
        Instant retryAt = NOW.plus(Duration.ofSeconds(30));
        Job failed = pendingJob(3).claimedBy("w", NOW, LEASE)
                .failedWithRetryAt(retryAt, "SMTP timeout", NOW);

        assertThat(failed.state()).isEqualTo(JobState.FAILED);
        assertThat(failed.scheduledAt()).isEqualTo(retryAt);
        assertThat(failed.lastError()).isEqualTo("SMTP timeout");
        assertThat(failed.lockedBy()).isNull();
        assertThat(failed.dueAt(NOW)).isFalse();
        assertThat(failed.dueAt(retryAt)).isTrue();
    }

    @Test
    @DisplayName("an expired lease is detectable and sends the job back to PENDING")
    void expiredLeaseReturnsToPending() {
        Job running = pendingJob(3).claimedBy("worker-1", NOW, LEASE);

        assertThat(running.leaseExpiredAt(NOW.plus(LEASE).minusSeconds(1))).isFalse();
        assertThat(running.leaseExpiredAt(NOW.plus(LEASE))).isTrue();

        Job reclaimed = running.leaseExpired(NOW.plus(LEASE));
        assertThat(reclaimed.state()).isEqualTo(JobState.PENDING);
        assertThat(reclaimed.lockedBy()).isNull();
        // The attempt still counts against the budget — we simply never learned how it ended.
        assertThat(reclaimed.attempt()).isEqualTo(1);
        assertThat(reclaimed.lastError()).contains("worker-1");
    }

    @Test
    @DisplayName("reviving a dead job hands back a full retry budget")
    void reviveResetsAttempts() {
        Job dead = pendingJob(3).claimedBy("w", NOW, LEASE).deadLettered("gave up", NOW);
        Instant later = NOW.plus(Duration.ofHours(1));

        Job revived = dead.revivedForManualRetry(later);

        assertThat(revived.state()).isEqualTo(JobState.PENDING);
        assertThat(revived.attempt()).isZero();
        assertThat(revived.retriesRemaining()).isEqualTo(3);
        assertThat(revived.scheduledAt()).isEqualTo(later);
        assertThat(revived.createdAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("transitions the lifecycle forbids are rejected on the job too")
    void illegalTransitionsRejected() {
        Job pending = pendingJob(3);

        assertThatThrownBy(() -> pending.completed(NOW))
                .isInstanceOf(IllegalJobTransitionException.class);
        assertThatThrownBy(() -> pending.failedWithRetryAt(NOW, "x", NOW))
                .isInstanceOf(IllegalJobTransitionException.class);

        Job completed = pending.claimedBy("w", NOW, LEASE).completed(NOW);
        assertThatThrownBy(() -> completed.claimedBy("w", NOW, LEASE))
                .isInstanceOf(IllegalJobTransitionException.class);
    }

    @Test
    @DisplayName("a future scheduledAt starts the job SCHEDULED rather than PENDING")
    void futureSubmissionIsScheduled() {
        Instant runAt = NOW.plus(Duration.ofHours(2));
        Job job = Job.newJob(UUID.randomUUID(),
                new JobSubmission("send-email", "{}", 0, 3, runAt), NOW);

        assertThat(job.state()).isEqualTo(JobState.SCHEDULED);
        assertThat(job.dueAt(NOW)).isFalse();
        assertThat(job.dueAt(runAt)).isTrue();
    }

    @Test
    @DisplayName("invalid field values are rejected at construction")
    void validatesFields() {
        assertThatThrownBy(() -> new JobSubmission("", "{}", 0, 3, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");
        assertThatThrownBy(() -> new JobSubmission("t", "{}", 0, -1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRetries");
    }
}
