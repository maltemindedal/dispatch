package dev.dispatch.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.store.JobStore;
import dev.dispatch.core.store.JobStoreException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Schema bootstrap, including the case that actually bites: several instances starting at once.
 *
 * <p>{@code CREATE TABLE IF NOT EXISTS} reads as concurrency-safe and is not, on PostgreSQL. Two
 * sessions both find the table missing and both try to create it; the loser fails with a unique
 * violation on the {@code pg_type} catalog. Rolling two replicas out together is the ordinary way
 * to hit this, so it gets a test.
 */
@Testcontainers
@DisplayName("Schema initialization")
class JobSchemaTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(PostgresTestSupport.IMAGE)
                    .withDatabaseName("dispatch")
                    .withUsername("dispatch")
                    .withPassword("dispatch");

    private static final OffsetDateTime LONG_AGO = OffsetDateTime.parse("2000-01-01T00:00:00Z");

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = PostgresTestSupport.pool(POSTGRES, 12);
        dropSchema();
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    private void dropSchema() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS jobs");
        }
    }

    @Test
    @DisplayName("creates a usable table from nothing")
    void createsSchema() {
        JobSchema.initialize(dataSource);

        JobStore store = JobStore.over(new JdbcJobRows(dataSource));
        assertThat(totalRows(store)).isZero();
    }

    @Test
    @DisplayName("is idempotent when run again")
    void isIdempotent() {
        JobSchema.initialize(dataSource);
        JobStore store = JobStore.over(new JdbcJobRows(dataSource));
        store.insert(new dev.dispatch.core.job.JobSubmission("t", "{}", 0, 3, null),
                java.time.Instant.now());

        assertThatCode(() -> JobSchema.initialize(dataSource)).doesNotThrowAnyException();

        // Re-running must not wipe anything: IF NOT EXISTS, not DROP AND CREATE.
        assertThat(totalRows(store)).isEqualTo(1);
    }

    @Test
    @DisplayName("survives several instances initializing at the same instant")
    void toleratesConcurrentInitialization() throws Exception {
        int instances = 12;
        // The collision window is narrow because PostgreSQL mostly serialises these, so the test runs
        // several rounds from an empty schema. Without the fix this reliably fails within a few
        // rounds; with it, none of them so much as log a warning.
        int rounds = 10;

        try (ExecutorService pool = Executors.newFixedThreadPool(instances)) {
            for (int round = 0; round < rounds; round++) {
                dropSchema();
                // A barrier rather than a plain thread start, so the creates genuinely collide
                // instead of politely queueing up behind each other.
                CyclicBarrier startLine = new CyclicBarrier(instances);
                List<Callable<Void>> starts = IntStream.range(0, instances)
                        .<Callable<Void>>mapToObj(i -> () -> {
                            startLine.await();
                            JobSchema.initialize(dataSource);
                            return null;
                        })
                        .toList();

                List<Future<Void>> results = pool.invokeAll(starts);
                int currentRound = round;
                for (Future<Void> result : results) {
                    // Any instance that failed to start would throw here.
                    assertThatCode(result::get)
                            .as("round %d", currentRound)
                            .doesNotThrowAnyException();
                }
            }
        }

        JobStore store = JobStore.over(new JdbcJobRows(dataSource));
        assertThat(totalRows(store)).isZero();
    }

    @Test
    @DisplayName("adds lease_id to a table created before the column existed, and keeps its rows")
    void upgradesATableWithoutLeaseId() throws Exception {
        createTableWithoutLeaseId();
        UUID pending = insertRowAsAnOlderInstance(JobState.PENDING, null, null);
        // Claimed by an instance that predates lease ids, whose lease has since lapsed.
        UUID abandoned = insertRowAsAnOlderInstance(
                JobState.RUNNING, LONG_AGO.plusMinutes(5), "old-worker");
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeQuery("SELECT lease_id FROM jobs"))
                    .as("the table really lacks the column before the upgrade")
                    .isInstanceOf(SQLException.class);
        }

        JobSchema.initialize(dataSource);

        JobStore store = JobStore.over(new JdbcJobRows(dataSource));
        assertThat(store.find(pending).orElseThrow().leaseId()).isNull();
        assertThat(store.find(abandoned).orElseThrow().leaseId()).isNull();
        // Both rows still work: the sweep reclaims the abandoned one, and each claim writes and
        // reads back a lease id that its outcome is then recorded under.
        assertThat(store.reclaimExpiredLeases(Instant.now(), 10)).isEqualTo(1);
        List<Job> claimed = store.claim("new-worker", 10, Duration.ofMinutes(5), Instant.now());
        assertThat(claimed).extracting(Job::id).containsExactlyInAnyOrder(pending, abandoned);
        for (Job job : claimed) {
            assertThat(job.leaseId()).isNotNull();
            assertThat(store.find(job.id()).orElseThrow().lease()).isEqualTo(job.lease());
            assertThat(store.complete(job.lease(), Instant.now())).isPresent();
        }
    }

    @Test
    @DisplayName("applying the script twice over an upgraded table is harmless")
    void upgradeIsIdempotent() throws Exception {
        createTableWithoutLeaseId();
        UUID pending = insertRowAsAnOlderInstance(JobState.PENDING, null, null);

        JobSchema.initialize(dataSource);
        assertThatCode(() -> JobSchema.initialize(dataSource)).doesNotThrowAnyException();

        JobStore store = JobStore.over(new JdbcJobRows(dataSource));
        assertThat(totalRows(store)).isEqualTo(1);
        Job claimed = store.claim("new-worker", 1, Duration.ofMinutes(5), Instant.now()).get(0);
        assertThat(claimed.id()).isEqualTo(pending);
        assertThat(store.find(pending).orElseThrow().leaseId()).isEqualTo(claimed.leaseId());
    }

    @Test
    @DisplayName("survives several instances upgrading the same table at the same instant")
    void toleratesConcurrentUpgrade() throws Exception {
        int instances = 12;
        int rounds = 10;

        try (ExecutorService pool = Executors.newFixedThreadPool(instances)) {
            for (int round = 0; round < rounds; round++) {
                dropSchema();
                createTableWithoutLeaseId();
                CyclicBarrier startLine = new CyclicBarrier(instances);
                List<Callable<Void>> starts = IntStream.range(0, instances)
                        .<Callable<Void>>mapToObj(i -> () -> {
                            startLine.await();
                            JobSchema.initialize(dataSource);
                            return null;
                        })
                        .toList();

                List<Future<Void>> results = pool.invokeAll(starts);
                int currentRound = round;
                for (Future<Void> result : results) {
                    assertThatCode(result::get)
                            .as("round %d", currentRound)
                            .doesNotThrowAnyException();
                }
            }
        }

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            assertThatCode(() -> statement.executeQuery("SELECT lease_id FROM jobs"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("starting over an up-to-date schema does not wait behind an open read of jobs")
    void startingDoesNotQueueBehindAReader() throws Exception {
        JobSchema.initialize(dataSource);
        HikariConfig impatient = PostgresTestSupport.config(POSTGRES, 2);
        // Gives up on a lock after a second, as docker-compose's lock_timeout does after ten.
        impatient.setConnectionInitSql("SET lock_timeout = '1s'");

        try (Connection reader = dataSource.getConnection();
                HikariDataSource restarting = new HikariDataSource(impatient)) {
            // A backup or a long report: an open transaction that has read jobs holds ACCESS SHARE
            // on it until it ends. ADD COLUMN wants ACCESS EXCLUSIVE, and every claim, insert and
            // read from every instance would queue behind that request.
            reader.setAutoCommit(false);
            try (Statement statement = reader.createStatement()) {
                statement.executeQuery("SELECT COUNT(*) FROM jobs").close();
            }

            assertThatCode(() -> JobSchema.initialize(restarting)).doesNotThrowAnyException();
            reader.rollback();
        }
    }

    @Test
    @DisplayName("a table that lacks a column the adapter reads fails at startup, not on the first claim")
    void verifiesEveryColumnTheAdapterReads() throws Exception {
        // Made by hand or by another tool: the script's CREATE TABLE skips it, the ALTER adds
        // lease_id and the indexes build, but last_error is missing.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE jobs (
                        id           UUID         PRIMARY KEY,
                        type         VARCHAR(255) NOT NULL,
                        payload      TEXT,
                        priority     INTEGER      NOT NULL DEFAULT 0,
                        max_retries  INTEGER      NOT NULL DEFAULT 3,
                        attempt      INTEGER      NOT NULL DEFAULT 0,
                        state        VARCHAR(16)  NOT NULL,
                        scheduled_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        updated_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        locked_until TIMESTAMP(6) WITH TIME ZONE,
                        locked_by    VARCHAR(255)
                    )""");
        }

        assertThatThrownBy(() -> JobSchema.initialize(dataSource))
                .isInstanceOf(JobStoreException.class)
                .hasMessageContaining("not usable");
    }

    @Test
    @DisplayName("the script parses into separate statements, comments and all")
    void readsStatements() {
        List<String> statements = JobSchema.readStatements();

        assertThat(statements).isNotEmpty();
        assertThat(statements).allSatisfy(sql -> {
            assertThat(sql).doesNotStartWith("--");
            assertThat(sql.trim()).isNotEmpty();
        });
        assertThat(statements.get(0)).startsWith("CREATE TABLE IF NOT EXISTS jobs");
        assertThat(statements).anySatisfy(sql ->
                assertThat(sql).contains("idx_jobs_claim"));
    }

    @Test
    @DisplayName("the state CHECK constraint lists exactly the JobState enum")
    void stateCheckMatchesTheEnum() {
        // The lifecycle is enforced in the domain model and repeated in DDL to keep the database
        // honest; this pins the two lists together so neither can drift.
        String createTable = JobSchema.readStatements().get(0);
        Matcher matcher = Pattern
                .compile("state\\s+IN\\s*\\(([^)]*)\\)", Pattern.DOTALL)
                .matcher(createTable);

        assertThat(matcher.find()).as("CHECK (state IN (...)) present").isTrue();
        Set<String> statesInSql = Arrays.stream(matcher.group(1).split(","))
                .map(name -> name.trim().replace("'", ""))
                .collect(Collectors.toSet());
        Set<String> statesInEnum = Arrays.stream(JobState.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertThat(statesInSql).isEqualTo(statesInEnum);
    }

    /** The {@code jobs} table as the first schema version created it, before {@code lease_id}. */
    private void createTableWithoutLeaseId() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE jobs (
                        id           UUID         PRIMARY KEY,
                        type         VARCHAR(255) NOT NULL,
                        payload      TEXT,
                        priority     INTEGER      NOT NULL DEFAULT 0,
                        max_retries  INTEGER      NOT NULL DEFAULT 3,
                        attempt      INTEGER      NOT NULL DEFAULT 0,
                        state        VARCHAR(16)  NOT NULL,
                        scheduled_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        updated_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        locked_until TIMESTAMP(6) WITH TIME ZONE,
                        locked_by    VARCHAR(255),
                        last_error   TEXT,
                        CONSTRAINT jobs_state_check CHECK (
                            state IN ('PENDING', 'SCHEDULED', 'RUNNING', 'COMPLETED', 'FAILED', 'DEAD')
                        ),
                        CONSTRAINT jobs_attempt_check CHECK (attempt >= 0 AND max_retries >= 0)
                    )""");
        }
    }

    /**
     * Inserts a row with only the columns an instance that predates lease ids writes, due since
     * long ago. A RUNNING row is its first attempt, held by {@code lockedBy} until
     * {@code lockedUntil}.
     */
    private UUID insertRowAsAnOlderInstance(JobState state, OffsetDateTime lockedUntil,
            String lockedBy) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO jobs (id, type, payload, priority, max_retries, attempt, state,"
                        + " scheduled_at, created_at, updated_at, locked_until, locked_by,"
                        + " last_error) VALUES (?, 'send-email', '{}', 0, 3, ?, ?, ?, ?, ?, ?, ?,"
                        + " NULL)")) {
            statement.setObject(1, id);
            statement.setInt(2, state == JobState.RUNNING ? 1 : 0);
            statement.setString(3, state.name());
            statement.setObject(4, LONG_AGO);
            statement.setObject(5, LONG_AGO);
            statement.setObject(6, LONG_AGO);
            if (lockedUntil == null) {
                statement.setNull(7, Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                statement.setObject(7, lockedUntil);
            }
            statement.setString(8, lockedBy);
            statement.executeUpdate();
        }
        return id;
    }

    private static long totalRows(JobStore store) {
        return store.countsByState().values().stream().mapToLong(Long::longValue).sum();
    }
}
