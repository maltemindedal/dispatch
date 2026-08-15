package dev.dispatch.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.dispatch.core.store.JobStore;
import dev.dispatch.core.testing.JobStoreContract;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;

/**
 * The shared store contract, run against H2 — the local dev database.
 *
 * <p>Passing here proves the SQL is portable enough that {@code ./gradlew bootRun} needs no
 * PostgreSQL. It does <em>not</em> prove multi-instance claiming, because H2's locking is coarser
 * than PostgreSQL's; {@link PostgresJdbcJobStoreTest} and
 * {@link ConcurrentInstancesIntegrationTest} cover that against the real thing.
 */
@DisplayName("JobStore on H2")
class H2JdbcJobStoreTest extends JobStoreContract {

    private static HikariDataSource dataSource;

    @Override
    protected JobStore createStore() {
        if (dataSource == null) {
            HikariConfig config = new HikariConfig();
            // A private in-memory database per test class run, kept alive by DB_CLOSE_DELAY.
            config.setJdbcUrl("jdbc:h2:mem:jobs-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            config.setUsername("sa");
            config.setPassword("");
            config.setMaximumPoolSize(16);
            dataSource = new HikariDataSource(config);
            JobSchema.initialize(dataSource);
        }
        JobStore store = JobStore.over(new JdbcJobRows(dataSource));
        store.deleteAll();
        return store;
    }

    @Override
    protected JobStore createStore(Supplier<UUID> ids) {
        createStore();
        return JobStore.over(new JdbcJobRows(dataSource), ids);
    }

    @AfterAll
    static void closePool() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }
}
