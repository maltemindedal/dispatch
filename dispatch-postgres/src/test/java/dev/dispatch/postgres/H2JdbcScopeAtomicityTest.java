package dev.dispatch.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;

@DisplayName("JDBC scope atomicity on H2")
class H2JdbcScopeAtomicityTest extends JdbcScopeAtomicityTest {

    private static HikariDataSource dataSource;

    @Override
    protected DataSource dataSource() {
        if (dataSource == null) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:h2:mem:scope-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            config.setUsername("sa");
            config.setPassword("");
            config.setMaximumPoolSize(4);
            dataSource = new HikariDataSource(config);
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
