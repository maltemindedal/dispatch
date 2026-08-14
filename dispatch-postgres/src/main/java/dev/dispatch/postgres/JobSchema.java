package dev.dispatch.postgres;

import dev.dispatch.core.store.JobStoreException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the {@code jobs} table and its indexes if they are not already there.
 *
 * <p>Deliberately not a migration tool. The DDL is idempotent ({@code CREATE ... IF NOT EXISTS}),
 * which is enough for a project that has exactly one schema version, and it keeps the dependency
 * list honest. The moment a second version exists, this should be replaced with Flyway or
 * Liquibase pointed at the same SQL — the statements would not need to change.
 *
 * <h2>Why {@code IF NOT EXISTS} is not enough on its own</h2>
 * In PostgreSQL, {@code CREATE TABLE IF NOT EXISTS} is <em>not</em> atomic against concurrent DDL.
 * Two instances starting at the same moment both find the table missing, both issue the create;
 * the second blocks on the first's lock and then fails — usually with a unique violation on the
 * {@code pg_type} catalog rather than anything as legible as "table already exists".
 *
 * <p>That is not a hypothetical. Two replicas rolling out together is the normal case, and it is
 * exactly when the race fires. So creation errors that mean "someone else got here first" are
 * treated as success, and the schema is verified afterwards rather than assumed.
 */
public final class JobSchema {

    private static final Logger log = LoggerFactory.getLogger(JobSchema.class);
    private static final String SCHEMA_RESOURCE = "/db/jobs-schema.sql";

    /**
     * SQL states meaning "this object already exists", including the catalog-level unique
     * violation PostgreSQL raises when two sessions create the same table simultaneously.
     */
    private static final Set<String> ALREADY_EXISTS_SQL_STATES = Set.of(
            "42P07",  // duplicate_table
            "42710",  // duplicate_object
            "42P16",  // invalid_table_definition, seen on some concurrent index races
            "23505"); // unique_violation on pg_type / pg_class

    private JobSchema() {
    }

    /** Applies the schema. Safe to call on every startup and from several instances at once. */
    public static void initialize(DataSource dataSource) {
        List<String> statements = readStatements();
        try (Connection connection = dataSource.getConnection()) {
            // One statement per transaction. With autoCommit off, the first failed CREATE would
            // abort the transaction and every statement after it would fail too.
            connection.setAutoCommit(true);
            for (String sql : statements) {
                executeToleratingConcurrentCreation(connection, sql);
            }
        } catch (SQLException e) {
            throw new JobStoreException("Failed to initialize the job queue schema", e);
        }
        verifySchemaUsable(dataSource);
        log.info("Job queue schema is present ({} statement(s) applied)", statements.size());
    }

    private static void executeToleratingConcurrentCreation(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            if (!ALREADY_EXISTS_SQL_STATES.contains(e.getSQLState())) {
                throw e;
            }
            // Another instance created this object first. By the time the error surfaces its
            // transaction has committed, so the object is there and we can carry on.
            log.debug("Schema object already created by another instance (SQLState {}): {}",
                    e.getSQLState(), e.getMessage());
        }
    }

    /**
     * Confirms the table is actually queryable. Without this, swallowing "already exists" errors
     * could hide a genuine failure and leave the application to discover it on its first claim.
     */
    private static void verifySchemaUsable(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SELECT COUNT(*) FROM jobs");
        } catch (SQLException e) {
            throw new JobStoreException(
                    "Job queue schema is not usable after initialization: " + e.getMessage(), e);
        }
    }

    /** The DDL, split into individual statements. Exposed for tests and for tooling. */
    public static List<String> readStatements() {
        // Comments come out first, then the split. The other order silently corrupts the script:
        // a prose semicolon inside a comment would cut a statement in half, and the tail of that
        // comment would be handed to the database as SQL.
        return Arrays.stream(stripComments(readSchemaSql()).split(";"))
                .map(String::trim)
                .filter(sql -> !sql.isEmpty())
                .toList();
    }

    private static String readSchemaSql() {
        try (InputStream in = JobSchema.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new JobStoreException("Schema resource not found on the classpath: "
                        + SCHEMA_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JobStoreException("Failed to read " + SCHEMA_RESOURCE, e);
        }
    }

    /**
     * Drops whole-line {@code --} comments. The statement splitter is naive on purpose — it is
     * only ever fed this one file, which keeps its semicolons out of string literals.
     */
    private static String stripComments(String sql) {
        return sql.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
    }
}
