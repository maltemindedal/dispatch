package dev.dispatch.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(PostgresTestSupport.IMAGE)
                    .withDatabaseName("dispatch")
                    .withUsername("dispatch")
                    .withPassword("dispatch");

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

        JdbcJobStore store = new JdbcJobStore(dataSource);
        assertThat(store.count()).isZero();
    }

    @Test
    @DisplayName("is idempotent when run again")
    void isIdempotent() {
        JobSchema.initialize(dataSource);
        JdbcJobStore store = new JdbcJobStore(dataSource);
        store.insert(new dev.dispatch.core.job.JobSubmission("t", "{}", 0, 3, null),
                java.time.Instant.now());

        assertThatCode(() -> JobSchema.initialize(dataSource)).doesNotThrowAnyException();

        // Re-running must not wipe anything: IF NOT EXISTS, not DROP AND CREATE.
        assertThat(store.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("survives several instances initializing at the same instant")
    void toleratesConcurrentInitialization() throws Exception {
        int instances = 12;
        // The collision window is narrow — PostgreSQL mostly serialises these — so the test runs
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

        JdbcJobStore store = new JdbcJobStore(dataSource);
        assertThat(store.count()).isZero();
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
}
