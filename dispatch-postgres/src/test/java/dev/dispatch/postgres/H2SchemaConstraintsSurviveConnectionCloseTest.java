package dev.dispatch.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.store.JobStore;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression test for an H2 defect: on 2.4.240 the {@code CHECK} constraints of the {@code jobs}
 * table stopped working once the connection that created the table was closed, and every later
 * insert or update failed with {@code Check constraint invalid: "JOBS_STATE_CHECK: "}.
 *
 * <p>It bites in the default {@code dev} profile: {@link JobSchema#initialize} runs on one pooled
 * connection and HikariCP retires that connection after its default 30-minute max lifetime, after
 * which the in-memory queue could not write. The pooled form of the failure takes half an hour
 * to appear, so this test reproduces it directly with a data source that really closes the
 * connection when it is returned.
 */
@DisplayName("H2 schema constraints")
class H2SchemaConstraintsSurviveConnectionCloseTest {

    private static JdbcDataSource freshDatabase() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:constraints-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    @Test
    @DisplayName("valid writes still work after the schema-creating connection is closed")
    void writesWorkAfterTheCreatingConnectionIsClosed() {
        JdbcDataSource dataSource = freshDatabase();
        JobSchema.initialize(dataSource);
        JobStore store = JobStore.over(new JdbcJobRows(dataSource));

        assertThatCode(() -> {
            store.insert(JobSubmission.of("send-email", "{}"), Instant.now());
            store.claim("worker", 1, java.time.Duration.ofMinutes(1), Instant.now());
        }).doesNotThrowAnyException();
        assertThat(store.countsByState().values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the constraints are still enforced, not merely tolerated")
    void invalidStateIsStillRejected() throws SQLException {
        JdbcDataSource dataSource = freshDatabase();
        JobSchema.initialize(dataSource);

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate(
                    "INSERT INTO jobs (id, type, state, scheduled_at, created_at, updated_at) "
                    + "VALUES ('" + UUID.randomUUID() + "', 't', 'BOGUS', CURRENT_TIMESTAMP, "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(
                    "INSERT INTO jobs (id, type, state, attempt, scheduled_at, created_at, "
                    + "updated_at) VALUES ('" + UUID.randomUUID() + "', 't', 'PENDING', -1, "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
