package dev.dispatch.postgres;

import com.zaxxer.hikari.HikariDataSource;
import dev.dispatch.core.store.JobStore;
import dev.dispatch.core.testing.JobStoreContract;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The shared store contract, run against real PostgreSQL in a container.
 *
 * <p>Same suite as the in-memory store and the H2 store. Three implementations, one set of
 * expectations — which is what "the persistence layer sits behind an interface" has to mean if it
 * is going to mean anything.
 */
@Testcontainers
@DisplayName("JdbcJobStore on PostgreSQL")
class PostgresJdbcJobStoreTest extends JobStoreContract {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(PostgresTestSupport.IMAGE)
                    .withDatabaseName("dispatch")
                    .withUsername("dispatch")
                    .withPassword("dispatch");

    private static HikariDataSource dataSource;

    @Override
    protected JobStore createStore() {
        if (dataSource == null) {
            dataSource = PostgresTestSupport.pool(POSTGRES, 16);
            JobSchema.initialize(dataSource);
        }
        JdbcJobStore store = new JdbcJobStore(dataSource);
        store.deleteAll();
        return store;
    }

    @Override
    protected JobStore createStore(Supplier<UUID> ids) {
        createStore();
        return new JdbcJobStore(dataSource, ids);
    }

    @AfterAll
    static void closePool() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }
}
