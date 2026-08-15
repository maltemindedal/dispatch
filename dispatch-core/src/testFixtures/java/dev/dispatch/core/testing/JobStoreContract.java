package dev.dispatch.core.testing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobActionResult;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.store.JobFilter;
import dev.dispatch.core.store.JobStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The behaviour every {@link JobStore} must exhibit, written once and run against each
 * implementation.
 *
 * <p>This is the payoff of putting persistence behind an interface: the in-memory store and the
 * PostgreSQL store are not merely swappable in principle, they are held to the same suite in
 * practice — including the claim-exclusivity test, which is the property the whole design rests on.
 *
 * <p>Subclasses supply a fresh, empty store from {@link #createStore()} and an id-injected
 * variant from {@link #createStore(Supplier)}.
 */
public abstract class JobStoreContract {

    protected static final Duration LEASE = Duration.ofMinutes(5);
    protected static final String WORKER = "worker-a";
    protected static final String OTHER_WORKER = "worker-b";

    protected MutableClock clock;
    protected JobStore store;

    /** @return an empty store; called before every test */
    protected abstract JobStore createStore();

    /** @return an empty store whose new-job ids come from {@code ids} */
    protected abstract JobStore createStore(Supplier<UUID> ids);

    @BeforeEach
    void setUpContract() {
        clock = MutableClock.atEpoch();
        store = createStore();
    }

    @AfterEach
    void tearDownContract() throws Exception {
        if (store != null) {
            store.close();
        }
    }

    protected Instant now() {
        return clock.instant();
    }

    protected Job insertDue(String type, int priority) {
        return store.insert(
                new JobSubmission(type, "{\"to\":\"a@b.c\"}", priority, 3, null), now());
    }

    protected Job insertDue() {
        return insertDue("send-email", 0);
    }

    protected Job reload(Job job) {
        return store.find(job.id()).orElseThrow(() -> new AssertionError("job vanished: " + job.id()));
    }

    // ------------------------------------------------------------------ insert

    @Nested
    @DisplayName("insert")
    class Insert {

        @Test
        @DisplayName("a job due now starts PENDING")
        void dueJobStartsPending() {
            Job job = insertDue();

            assertThat(job.state()).isEqualTo(JobState.PENDING);
            assertThat(job.attempt()).isZero();
            assertThat(job.scheduledAt()).isEqualTo(now());
            assertThat(job.lockedBy()).isNull();
            assertThat(job.lockedUntil()).isNull();
            assertThat(reload(job)).isEqualTo(job);
        }

        @Test
        @DisplayName("a job with a future scheduledAt starts SCHEDULED")
        void futureJobStartsScheduled() {
            Instant runAt = now().plus(Duration.ofMinutes(10));
            Job job = store.insert(
                    new JobSubmission("send-email", "{}", 0, 3, runAt), now());

            assertThat(job.state()).isEqualTo(JobState.SCHEDULED);
            assertThat(job.scheduledAt()).isEqualTo(runAt);
            assertThat(reload(job).state()).isEqualTo(JobState.SCHEDULED);
        }

        @Test
        @DisplayName("payload, priority and retry budget round-trip unchanged")
        void fieldsRoundTrip() {
            String payload = "{\"to\":\"someone@example.com\",\"subject\":\"héllo → ✉\"}";
            Job job = store.insert(new JobSubmission("send-email", payload, 7, 5, null), now());

            Job loaded = reload(job);
            assertThat(loaded.payload()).isEqualTo(payload);
            assertThat(loaded.priority()).isEqualTo(7);
            assertThat(loaded.maxRetries()).isEqualTo(5);
            assertThat(loaded.type()).isEqualTo("send-email");
        }

        @Test
        @DisplayName("find on an unknown id is empty rather than an error")
        void findUnknownIsEmpty() {
            assertThat(store.find(UUID.randomUUID())).isEmpty();
        }
    }

    // ------------------------------------------------------------------- claim

    @Nested
    @DisplayName("claim")
    class Claim {

        @Test
        @DisplayName("takes a PENDING job, opens a lease and counts the attempt")
        void takesPendingJob() {
            Job job = insertDue();

            List<Job> claimed = store.claim(WORKER, 10, LEASE, now());

            assertThat(claimed).hasSize(1);
            Job running = claimed.get(0);
            assertThat(running.id()).isEqualTo(job.id());
            assertThat(running.state()).isEqualTo(JobState.RUNNING);
            assertThat(running.attempt()).isEqualTo(1);
            assertThat(running.lockedBy()).isEqualTo(WORKER);
            assertThat(running.lockedUntil()).isEqualTo(now().plus(LEASE));
            assertThat(reload(job)).isEqualTo(running);
        }

        @Test
        @DisplayName("leaves SCHEDULED jobs alone until they are promoted")
        void ignoresScheduledJobs() {
            store.insert(new JobSubmission("send-email", "{}", 0, 3,
                    now().plus(Duration.ofMinutes(10))), now());

            assertThat(store.claim(WORKER, 10, LEASE, now())).isEmpty();
        }

        @Test
        @DisplayName("never hands the same job to two callers")
        void isExclusive() {
            Job job = insertDue();

            List<Job> first = store.claim(WORKER, 10, LEASE, now());
            List<Job> second = store.claim(OTHER_WORKER, 10, LEASE, now());

            assertThat(first).extracting(Job::id).containsExactly(job.id());
            assertThat(second).isEmpty();
        }

        @Test
        @DisplayName("returns highest priority first, then oldest")
        void ordersByPriorityThenAge() {
            Job low = insertDue("send-email", 0);
            clock.advance(Duration.ofMillis(10));
            Job high = insertDue("send-email", 10);
            clock.advance(Duration.ofMillis(10));
            Job highButNewer = insertDue("send-email", 10);
            clock.advance(Duration.ofMillis(10));
            Job medium = insertDue("send-email", 5);

            List<Job> claimed = store.claim(WORKER, 10, LEASE, now());

            assertThat(claimed).extracting(Job::id)
                    .containsExactly(high.id(), highButNewer.id(), medium.id(), low.id());
        }

        @Test
        @DisplayName("a capped claim takes the top of the order, not an arbitrary row")
        void cappedClaimTakesTheHighestPriority() {
            insertDue("send-email", 1);
            clock.advance(Duration.ofMillis(10));
            Job high = insertDue("send-email", 9);
            clock.advance(Duration.ofMillis(10));
            insertDue("send-email", 5);

            assertThat(store.claim(WORKER, 1, LEASE, now()))
                    .extracting(Job::id)
                    .containsExactly(high.id());
        }

        @Test
        @DisplayName("honours the batch limit")
        void honoursLimit() {
            for (int i = 0; i < 5; i++) {
                insertDue();
            }

            assertThat(store.claim(WORKER, 2, LEASE, now())).hasSize(2);
            assertThat(store.claim(WORKER, 10, LEASE, now())).hasSize(3);
            assertThat(store.claim(WORKER, 10, LEASE, now())).isEmpty();
        }

        @Test
        @DisplayName("hands each job to exactly one of many concurrent claimants")
        void isExclusiveUnderConcurrency() throws Exception {
            int jobCount = 200;
            int claimants = 8;
            for (int i = 0; i < jobCount; i++) {
                insertDue();
            }

            List<UUID> claimedIds = new CopyOnWriteArrayList<>();
            CountDownLatch startLine = new CountDownLatch(1);
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < claimants; i++) {
                    String workerId = "claimant-" + i;
                    pool.execute(() -> {
                        try {
                            startLine.await();
                            while (claimedIds.size() < jobCount && System.nanoTime() < deadline) {
                                List<Job> batch = store.claim(workerId, 3, LEASE, now());
                                if (batch.isEmpty()) {
                                    // An empty batch does not prove the queue is empty: under
                                    // SKIP LOCKED it can also mean a peer currently holds every
                                    // row this claimant can see. Yield and look again.
                                    Thread.sleep(1);
                                    continue;
                                }
                                batch.forEach(job -> claimedIds.add(job.id()));
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
                startLine.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            }

            // The property that matters: every job claimed once, none claimed twice.
            assertThat(claimedIds).hasSize(jobCount).doesNotHaveDuplicates();
            assertThat(store.countsByState().get(JobState.RUNNING)).isEqualTo(jobCount);
        }
    }

    // -------------------------------------------------------- recording results

    @Nested
    @DisplayName("recording a result")
    class RecordingResults {

        @Test
        @DisplayName("complete moves a claimed job to COMPLETED and releases the lease")
        void completeReleasesLease() {
            Job job = insertDue();
            store.claim(WORKER, 1, LEASE, now());
            clock.advance(Duration.ofSeconds(2));

            Optional<Job> completed = store.complete(job.id(), WORKER, now());

            assertThat(completed).isPresent();
            assertThat(completed.get().state()).isEqualTo(JobState.COMPLETED);
            assertThat(completed.get().lockedBy()).isNull();
            assertThat(completed.get().lockedUntil()).isNull();
            assertThat(completed.get().updatedAt()).isEqualTo(now());
        }

        @Test
        @DisplayName("a worker that no longer holds the lease cannot record anything")
        void rejectsForeignWorker() {
            Job job = insertDue();
            store.claim(WORKER, 1, LEASE, now());

            assertThat(store.complete(job.id(), OTHER_WORKER, now())).isEmpty();
            assertThat(store.fail(job.id(), OTHER_WORKER, "nope", now(), now())).isEmpty();
            assertThat(store.deadLetter(job.id(), OTHER_WORKER, "nope", now())).isEmpty();
            assertThat(reload(job).state()).isEqualTo(JobState.RUNNING);
        }

        @Test
        @DisplayName("fail parks the job in FAILED until its backoff elapses")
        void failParksJob() {
            Job job = insertDue();
            store.claim(WORKER, 1, LEASE, now());
            Instant retryAt = now().plus(Duration.ofSeconds(30));

            Optional<Job> failed = store.fail(job.id(), WORKER, "boom", retryAt, now());

            assertThat(failed).isPresent();
            assertThat(failed.get().state()).isEqualTo(JobState.FAILED);
            assertThat(failed.get().scheduledAt()).isEqualTo(retryAt);
            assertThat(failed.get().lastError()).isEqualTo("boom");
            assertThat(failed.get().attempt()).isEqualTo(1);
            assertThat(failed.get().lockedBy()).isNull();
            // Still not claimable: the backoff has not elapsed.
            assertThat(store.claim(WORKER, 10, LEASE, now())).isEmpty();
        }

        @Test
        @DisplayName("deadLetter is terminal and keeps the error for inspection")
        void deadLetterIsTerminal() {
            Job job = insertDue();
            store.claim(WORKER, 1, LEASE, now());

            Optional<Job> dead = store.deadLetter(job.id(), WORKER, "gave up", now());

            assertThat(dead).isPresent();
            assertThat(dead.get().state()).isEqualTo(JobState.DEAD);
            assertThat(dead.get().lastError()).isEqualTo("gave up");
            assertThat(store.claim(WORKER, 10, LEASE, now())).isEmpty();
        }

        @Test
        @DisplayName("recording against a job that was never claimed does nothing")
        void rejectsUnclaimedJob() {
            Job job = insertDue();

            assertThat(store.complete(job.id(), WORKER, now())).isEmpty();
            assertThat(reload(job).state()).isEqualTo(JobState.PENDING);
        }
    }

    // ------------------------------------------------------------- maintenance

    @Nested
    @DisplayName("maintenance")
    class Maintenance {

        @Test
        @DisplayName("promotes SCHEDULED jobs once their time arrives")
        void promotesDueScheduledJobs() {
            Job job = store.insert(new JobSubmission("send-email", "{}", 0, 3,
                    now().plus(Duration.ofMinutes(5))), now());

            assertThat(store.promoteDueJobs(now(), 100)).isZero();

            clock.advance(Duration.ofMinutes(5));
            assertThat(store.promoteDueJobs(now(), 100)).isEqualTo(1);
            assertThat(reload(job).state()).isEqualTo(JobState.PENDING);
            assertThat(store.claim(WORKER, 10, LEASE, now())).hasSize(1);
        }

        @Test
        @DisplayName("promotes FAILED jobs once their backoff elapses")
        void promotesJobsOutOfBackoff() {
            Job job = insertDue();
            store.claim(WORKER, 1, LEASE, now());
            store.fail(job.id(), WORKER, "boom", now().plus(Duration.ofSeconds(30)), now());

            assertThat(store.promoteDueJobs(now(), 100)).isZero();

            clock.advance(Duration.ofSeconds(30));
            assertThat(store.promoteDueJobs(now(), 100)).isEqualTo(1);

            Job promoted = reload(job);
            assertThat(promoted.state()).isEqualTo(JobState.PENDING);
            // The attempt counter survives promotion: the retry budget must keep shrinking.
            assertThat(promoted.attempt()).isEqualTo(1);
        }

        @Test
        @DisplayName("reclaims a job whose worker died and let the lease lapse")
        void reclaimsExpiredLease() {
            Job job = insertDue();
            store.claim(WORKER, 1, LEASE, now());

            // Lease still live: nothing to reclaim.
            clock.advance(LEASE.minusSeconds(1));
            assertThat(store.reclaimExpiredLeases(now(), 100)).isZero();

            clock.advance(Duration.ofSeconds(2));
            assertThat(store.reclaimExpiredLeases(now(), 100)).isEqualTo(1);

            Job reclaimed = reload(job);
            assertThat(reclaimed.state()).isEqualTo(JobState.PENDING);
            assertThat(reclaimed.lockedBy()).isNull();
            assertThat(reclaimed.attempt()).isEqualTo(1);
            assertThat(store.claim(OTHER_WORKER, 10, LEASE, now())).hasSize(1);
        }

        @Test
        @DisplayName("the original worker cannot record a result after being reclaimed")
        void reclaimedJobRejectsOriginalWorker() {
            Job job = insertDue();
            store.claim(WORKER, 1, LEASE, now());
            clock.advance(LEASE.plusSeconds(1));
            store.reclaimExpiredLeases(now(), 100);
            store.claim(OTHER_WORKER, 1, LEASE, now());

            // The zombie finally finishes and tries to report success. It must not be believed.
            assertThat(store.complete(job.id(), WORKER, now())).isEmpty();
            assertThat(reload(job).lockedBy()).isEqualTo(OTHER_WORKER);
        }

        @Test
        @DisplayName("respects the batch limit")
        void respectsBatchLimit() {
            for (int i = 0; i < 5; i++) {
                store.insert(new JobSubmission("send-email", "{}", 0, 3,
                        now().plus(Duration.ofSeconds(1))), now());
            }
            clock.advance(Duration.ofSeconds(2));

            assertThat(store.promoteDueJobs(now(), 2)).isEqualTo(2);
            assertThat(store.promoteDueJobs(now(), 100)).isEqualTo(3);
        }

        @Test
        @DisplayName("a capped promotion takes the jobs that came due first")
        void cappedPromotionTakesTheEarliestDue() {
            Job soon = store.insert(new JobSubmission("send-email", "{}", 0, 3,
                    now().plus(Duration.ofSeconds(1))), now());
            Job later = store.insert(new JobSubmission("send-email", "{}", 0, 3,
                    now().plus(Duration.ofSeconds(2))), now());
            clock.advance(Duration.ofSeconds(3));

            assertThat(store.promoteDueJobs(now(), 1)).isEqualTo(1);

            assertThat(reload(soon).state()).isEqualTo(JobState.PENDING);
            assertThat(reload(later).state()).isEqualTo(JobState.SCHEDULED);
        }

        @Test
        @DisplayName("a capped reclaim takes the leases that lapsed longest ago")
        void cappedReclaimTakesTheOldestLeases() {
            // Three jobs claimed a minute apart, so their leases lapse in that same order.
            insertDue();
            insertDue();
            insertDue();
            Job first = store.claim(WORKER, 1, LEASE, now()).get(0);
            clock.advance(Duration.ofMinutes(1));
            Job second = store.claim(WORKER, 1, LEASE, now()).get(0);
            clock.advance(Duration.ofMinutes(1));
            Job third = store.claim(WORKER, 1, LEASE, now()).get(0);

            clock.advance(LEASE.plusMinutes(1));
            assertThat(store.reclaimExpiredLeases(now(), 2)).isEqualTo(2);

            // A cap must take the most-abandoned work, not an arbitrary two of the three —
            // otherwise a queue that is always over the cap can starve one job indefinitely.
            assertThat(reload(first).state()).isEqualTo(JobState.PENDING);
            assertThat(reload(second).state()).isEqualTo(JobState.PENDING);
            assertThat(reload(third).state()).isEqualTo(JobState.RUNNING);
        }
    }

    // ------------------------------------------------------- operator actions

    @Nested
    @DisplayName("operator actions")
    class OperatorActions {

        @Test
        @DisplayName("cancel removes a job that has not started and returns its final snapshot")
        void cancelRemovesPendingJob() {
            Job pending = insertDue();
            Job scheduled = store.insert(new JobSubmission("send-email", "{}", 0, 3,
                    now().plus(Duration.ofMinutes(5))), now());

            assertThat(store.cancel(pending.id()))
                    .isInstanceOfSatisfying(JobActionResult.Done.class,
                            done -> assertThat(done.job().id()).isEqualTo(pending.id()));
            assertThat(store.cancel(scheduled.id()))
                    .isInstanceOf(JobActionResult.Done.class);
            assertThat(store.find(pending.id())).isEmpty();
            assertThat(store.find(scheduled.id())).isEmpty();
        }

        @Test
        @DisplayName("cancel refuses a running job, reporting the state it observed")
        void cancelRefusesRunningJob() {
            Job job = insertDue();
            store.claim(WORKER, 1, LEASE, now());

            assertThat(store.cancel(job.id()))
                    .isInstanceOfSatisfying(JobActionResult.WrongState.class, refusal -> {
                        assertThat(refusal.observed().state()).isEqualTo(JobState.RUNNING);
                        assertThat(refusal.allowedStates())
                                .containsExactlyInAnyOrder(JobState.PENDING, JobState.SCHEDULED);
                    });
            assertThat(reload(job).state()).isEqualTo(JobState.RUNNING);
        }

        @Test
        @DisplayName("cancel on an unknown id reports not found")
        void cancelUnknownJob() {
            UUID id = UUID.randomUUID();
            assertThat(store.cancel(id))
                    .isInstanceOfSatisfying(JobActionResult.NotFound.class,
                            missing -> assertThat(missing.id()).isEqualTo(id));
        }

        @Test
        @DisplayName("a dead job can be revived, with a fresh retry budget")
        void requeueDeadJob() {
            Job job = insertDue();
            store.claim(WORKER, 1, LEASE, now());
            store.deadLetter(job.id(), WORKER, "gave up", now());
            clock.advance(Duration.ofMinutes(1));

            assertThat(store.requeueDeadJob(job.id(), now()))
                    .isInstanceOfSatisfying(JobActionResult.Done.class, done -> {
                        assertThat(done.job().state()).isEqualTo(JobState.PENDING);
                        assertThat(done.job().attempt()).isZero();
                        assertThat(done.job().scheduledAt()).isEqualTo(now());
                    });
            assertThat(store.claim(WORKER, 10, LEASE, now())).hasSize(1);
        }

        @Test
        @DisplayName("deleteAll empties the store, whatever the states")
        void deleteAllEmptiesTheStore() {
            insertDue();
            store.insert(new JobSubmission("send-email", "{}", 0, 3,
                    now().plus(Duration.ofMinutes(5))), now());
            store.claim(WORKER, 1, LEASE, now());

            store.deleteAll();

            assertThat(store.countsByState().values()).containsOnly(0L);
            assertThat(store.list(new JobFilter(null, null, 10, 0))).isEmpty();
        }

        @Test
        @DisplayName("only DEAD jobs can be revived, and the refusal says why")
        void requeueRefusesLiveJob() {
            Job job = insertDue();

            assertThat(store.requeueDeadJob(job.id(), now()))
                    .isInstanceOfSatisfying(JobActionResult.WrongState.class, refusal -> {
                        assertThat(refusal.observed().state()).isEqualTo(JobState.PENDING);
                        assertThat(refusal.allowedStates()).containsExactly(JobState.DEAD);
                    });
            assertThat(store.requeueDeadJob(UUID.randomUUID(), now()))
                    .isInstanceOf(JobActionResult.NotFound.class);
        }
    }

    // ------------------------------------------------------------ querying

    @Nested
    @DisplayName("querying")
    class Querying {

        @Test
        @DisplayName("counts report depth per state")
        void countsByState() {
            insertDue();
            insertDue();
            store.insert(new JobSubmission("send-email", "{}", 0, 3,
                    now().plus(Duration.ofMinutes(5))), now());
            Job running = insertDue();
            store.claim(WORKER, 1, LEASE, now());

            assertThat(store.countsByState())
                    .containsEntry(JobState.PENDING, 2L)
                    .containsEntry(JobState.SCHEDULED, 1L)
                    .containsEntry(JobState.RUNNING, 1L)
                    .containsEntry(JobState.COMPLETED, 0L)
                    .containsEntry(JobState.FAILED, 0L)
                    .containsEntry(JobState.DEAD, 0L)
                    .as("every state is present, zero-filled")
                    .hasSize(JobState.values().length);
            assertThat(running.id()).isNotNull();
        }

        @Test
        @DisplayName("list filters by state and by type")
        void listFilters() {
            List<Job> emails = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                emails.add(insertDue("send-email", 0));
                clock.advance(Duration.ofMillis(1));
            }
            // Higher priority, so the single claim below deterministically takes this one and
            // leaves the three emails PENDING.
            Job resize = insertDue("resize-image", 10);
            assertThat(store.claim(WORKER, 1, LEASE, now())).extracting(Job::id)
                    .containsExactly(resize.id());

            assertThat(store.list(JobFilter.byType("resize-image")))
                    .extracting(Job::id).containsExactly(resize.id());
            assertThat(store.list(JobFilter.byState(JobState.RUNNING))).hasSize(1);
            assertThat(store.list(JobFilter.byState(JobState.PENDING)))
                    .extracting(Job::id)
                    .containsExactlyInAnyOrderElementsOf(emails.stream().map(Job::id).toList());
            assertThat(store.list(JobFilter.all())).hasSize(4);
            assertThat(store.list(JobFilter.all().withLimit(2))).hasSize(2);
        }

        @Test
        @DisplayName("list pages through results without repeating or skipping rows")
        void listPages() {
            for (int i = 0; i < 5; i++) {
                insertDue();
                clock.advance(Duration.ofMillis(1));
            }

            List<Job> firstPage = store.list(JobFilter.all().withLimit(2));
            List<Job> secondPage = store.list(JobFilter.all().withLimit(2).withOffset(2));
            List<Job> thirdPage = store.list(JobFilter.all().withLimit(2).withOffset(4));

            assertThat(firstPage).hasSize(2);
            assertThat(secondPage).hasSize(2);
            assertThat(thirdPage).hasSize(1);
            assertThat(List.of(firstPage, secondPage, thirdPage).stream()
                    .flatMap(List::stream).map(Job::id).toList())
                    .hasSize(5)
                    .doesNotHaveDuplicates();
        }
    }

    // ------------------------------------------------------------ seam symmetry

    @Nested
    @DisplayName("seam symmetry")
    class SeamSymmetry {

        @Test
        @DisplayName("job ids come from the injected id source")
        void idsComeFromTheInjectedSource() throws Exception {
            UUID pinned = UUID.fromString("00000000-0000-0000-0000-000000000042");
            try (JobStore deterministic = createStore(() -> pinned)) {
                Job job = deterministic.insert(
                        new JobSubmission("send-email", "{}", 0, 3, null), now());

                assertThat(job.id()).isEqualTo(pinned);
                assertThat(deterministic.find(pinned)).isPresent();
            }
        }

        @Test
        @DisplayName("close is safe to call twice")
        void closeIsIdempotent() throws Exception {
            JobStore closeable = createStore();
            closeable.close();
            closeable.close();
        }
    }
}
