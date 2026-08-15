package dev.dispatch.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.zaxxer.hikari.HikariDataSource;
import dev.dispatch.core.engine.JobQueue;
import dev.dispatch.core.engine.QueueConfig;
import dev.dispatch.core.handler.InMemoryJobHandlerRegistry;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.retry.RetryPolicy;
import dev.dispatch.core.store.JobStore;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The test this whole design exists to pass: two application instances, one database, and no job
 * ever processed twice.
 *
 * <p>The two instances are as separate as they can be inside one JVM — their own connection pools,
 * their own stores, their own worker ids, their own dispatchers and sweepers. The only thing they
 * share is the {@code jobs} table, which is exactly the production arrangement: two containers
 * behind a load balancer, one PostgreSQL.
 *
 * <p>Nothing coordinates them. There is no leader, no lock service, no partitioning of work.
 * {@code FOR UPDATE SKIP LOCKED} is the entire mutual exclusion mechanism.
 */
@Testcontainers
@DisplayName("Two instances sharing one PostgreSQL database")
class ConcurrentInstancesIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(PostgresTestSupport.IMAGE)
                    .withDatabaseName("dispatch")
                    .withUsername("dispatch")
                    .withPassword("dispatch");

    /** job id -> the worker(s) that executed it. Any list longer than one is a double-process. */
    private final Map<UUID, List<String>> executions = new ConcurrentHashMap<>();

    /** Payloads in the order handlers started them, across both instances. */
    private final List<String> executionOrder = new CopyOnWriteArrayList<>();

    private final List<Instance> instances = new ArrayList<>();

    /** One simulated application instance: its own pool, its own store, its own workers. */
    private record Instance(String workerId, HikariDataSource dataSource, JobStore store,
            JobQueue queue) {
    }

    @BeforeEach
    void setUp() {
        HikariDataSource bootstrap = PostgresTestSupport.pool(POSTGRES, 2);
        try {
            JobSchema.initialize(bootstrap);
            JobStore.over(new JdbcJobRows(bootstrap)).deleteAll();
        } finally {
            bootstrap.close();
        }
        executions.clear();
        executionOrder.clear();
    }

    @AfterEach
    void tearDown() {
        instances.forEach(instance -> {
            instance.queue().shutdown(Duration.ofSeconds(20));
            instance.dataSource().close();
        });
        instances.clear();
    }

    private Instance startInstance(String workerId, Duration visibilityTimeout) {
        HikariDataSource dataSource = PostgresTestSupport.pool(POSTGRES, 8);
        JobStore store = JobStore.over(new JdbcJobRows(dataSource));

        InMemoryJobHandlerRegistry registry = new InMemoryJobHandlerRegistry();
        registry.register("shared-work", context -> {
            executions.computeIfAbsent(context.jobId(), id -> new CopyOnWriteArrayList<>())
                    .add(workerId);
            executionOrder.add(context.payload());
            // A little real work, so the claim windows genuinely overlap.
            Thread.sleep(5);
        });

        JobQueue queue = JobQueue.builder()
                .store(store)
                .registry(registry)
                .clock(Clock.systemUTC())
                .retryPolicy(RetryPolicy.fixed(Duration.ofSeconds(1)))
                .config(QueueConfig.builder()
                        .workerId(workerId)
                        .concurrency(8)
                        .claimBatchSize(4)
                        .pollInterval(Duration.ofMillis(25))
                        .maintenanceInterval(Duration.ofMillis(100))
                        .visibilityTimeout(visibilityTimeout)
                        .build())
                .build()
                .start();

        Instance instance = new Instance(workerId, dataSource, store, queue);
        instances.add(instance);
        return instance;
    }

    @Test
    @DisplayName("every job is processed exactly once across both instances")
    void noJobIsProcessedTwice() {
        Instance alpha = startInstance("instance-alpha", Duration.ofMinutes(5));
        Instance beta = startInstance("instance-beta", Duration.ofMinutes(5));

        int jobCount = 300;
        List<UUID> submitted = new ArrayList<>();
        for (int i = 0; i < jobCount; i++) {
            // Submitted through whichever instance the load balancer happened to pick.
            JobQueue submitter = (i % 2 == 0 ? alpha : beta).queue();
            submitted.add(submitter.submit(
                    new JobSubmission("shared-work", "{\"n\":" + i + "}", 0, 3, null)).id());
        }

        await().atMost(Duration.ofSeconds(90)).untilAsserted(() ->
                assertThat(alpha.store().countsByState().get(JobState.COMPLETED))
                        .isEqualTo(jobCount));

        // The assertion that matters: no job ran more than once.
        assertThat(executions).hasSize(jobCount);
        assertThat(executions.values()).allSatisfy(runners ->
                assertThat(runners).as("job executed by more than one worker").hasSize(1));
        assertThat(executions.keySet()).containsExactlyInAnyOrderElementsOf(submitted);

        // And both instances actually did work, so this is not passing because one sat idle.
        List<String> allRunners = executions.values().stream().flatMap(List::stream).toList();
        assertThat(allRunners).hasSize(jobCount);
        assertThat(allRunners).contains("instance-alpha");
        assertThat(allRunners).contains("instance-beta");
        assertThat(alpha.queue().metrics().claimed() + beta.queue().metrics().claimed())
                .isEqualTo(jobCount);

        assertThat(alpha.store().countsByState())
                .containsEntry(JobState.PENDING, 0L)
                .containsEntry(JobState.RUNNING, 0L)
                .containsEntry(JobState.FAILED, 0L)
                .containsEntry(JobState.DEAD, 0L);
    }

    @Test
    @DisplayName("claims are disjoint: the two instances never hold the same row")
    void claimsAreDisjoint() {
        HikariDataSource poolA = PostgresTestSupport.pool(POSTGRES, 4);
        HikariDataSource poolB = PostgresTestSupport.pool(POSTGRES, 4);
        try {
            JobStore storeA = JobStore.over(new JdbcJobRows(poolA));
            JobStore storeB = JobStore.over(new JdbcJobRows(poolB));
            java.time.Instant now = java.time.Instant.now();
            for (int i = 0; i < 20; i++) {
                storeA.insert(new JobSubmission("shared-work", "{}", 0, 3, null), now);
            }

            List<Job> claimedByA = storeA.claim("a", 10, Duration.ofMinutes(5), now);
            List<Job> claimedByB = storeB.claim("b", 10, Duration.ofMinutes(5), now);

            assertThat(claimedByA).hasSize(10);
            // SKIP LOCKED means B walked past A's rows rather than blocking on them.
            assertThat(claimedByB).hasSize(10);
            assertThat(claimedByA).extracting(Job::id)
                    .doesNotContainAnyElementsOf(claimedByB.stream().map(Job::id).toList());
            assertThat(storeA.claim("a", 10, Duration.ofMinutes(5), now)).isEmpty();
        } finally {
            poolA.close();
            poolB.close();
        }
    }

    @Test
    @DisplayName("a job orphaned by one instance is recovered by the other")
    void orphanedJobIsRecoveredByPeer() {
        Duration visibilityTimeout = Duration.ofSeconds(2);

        // Simulate an instance that claimed a job and was then killed: the row is RUNNING, held
        // by a worker id that will never report a result. Staged before the survivor starts —
        // a live instance's dispatcher would legitimately race the doomed claim for the row.
        java.time.Instant now = java.time.Instant.now();
        Job job;
        HikariDataSource staging = PostgresTestSupport.pool(POSTGRES, 2);
        try {
            JobStore stagingStore = JobStore.over(new JdbcJobRows(staging));
            job = stagingStore.insert(
                    new JobSubmission("shared-work", "{\"orphan\":true}", 0, 3, null), now);
            List<Job> stolen = stagingStore.claim("instance-doomed", 1, visibilityTimeout, now);
            assertThat(stolen).extracting(Job::id).containsExactly(job.id());
        } finally {
            staging.close();
        }

        Instance survivor = startInstance("instance-survivor", visibilityTimeout);

        // No coordination, no failover protocol: the surviving instance's sweeper simply notices
        // the lapsed lease and puts the job back.
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(survivor.store().find(job.id()).orElseThrow().state())
                    .isEqualTo(JobState.COMPLETED);
            assertThat(executions.get(job.id())).containsExactly("instance-survivor");
        });
        assertThat(survivor.store().find(job.id()).orElseThrow().attempt()).isEqualTo(2);
    }

    @Test
    @DisplayName("priority ordering holds across instances, not just within one")
    void priorityHoldsAcrossInstances() {
        // Load the whole backlog before anything is running, so no instance can get a head start
        // on the low-priority jobs and make the ordering claim untestable.
        HikariDataSource loader = PostgresTestSupport.pool(POSTGRES, 2);
        List<UUID> urgent = new ArrayList<>();
        try {
            JobStore store = JobStore.over(new JdbcJobRows(loader));
            java.time.Instant now = java.time.Instant.now();
            for (int i = 0; i < 40; i++) {
                store.insert(new JobSubmission("shared-work", "bulk-" + i, 0, 3, null), now);
            }
            for (int i = 0; i < 4; i++) {
                urgent.add(store.insert(
                        new JobSubmission("shared-work", "urgent-" + i, 100, 3, null), now).id());
            }
        } finally {
            loader.close();
        }

        Instance alpha = startInstance("instance-alpha", Duration.ofMinutes(5));
        startInstance("instance-beta", Duration.ofMinutes(5));

        await().atMost(Duration.ofSeconds(90)).untilAsserted(() ->
                assertThat(alpha.store().countsByState().get(JobState.COMPLETED)).isEqualTo(44));

        // Two instances claiming batches of four means the first claims cover the top eight rows
        // by priority. The four urgent jobs are in there, so they start well before the bulk of
        // the backlog even though they were queued last.
        assertThat(executionOrder).hasSize(44);
        assertThat(executionOrder.subList(0, 12))
                .contains("urgent-0", "urgent-1", "urgent-2", "urgent-3");
        assertThat(executions).hasSize(44);
        assertThat(executions.keySet()).containsAll(urgent);
        assertThat(executions.values()).allSatisfy(runners -> assertThat(runners).hasSize(1));
    }
}
