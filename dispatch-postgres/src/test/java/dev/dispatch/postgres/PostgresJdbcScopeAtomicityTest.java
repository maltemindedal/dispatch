package dev.dispatch.postgres;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@DisplayName("JDBC scope atomicity on PostgreSQL")
class PostgresJdbcScopeAtomicityTest extends JdbcScopeAtomicityTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(PostgresTestSupport.IMAGE)
                    .withDatabaseName("dispatch")
                    .withUsername("dispatch")
                    .withPassword("dispatch");

    private static HikariDataSource dataSource;

    @Override
    protected DataSource dataSource() {
        if (dataSource == null) {
            dataSource = PostgresTestSupport.pool(POSTGRES, 4);
            JobSchema.initialize(dataSource);
        }
        return dataSource;
    }

    @AfterAll
    static void closePool() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }
}
