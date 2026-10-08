package dev.dispatch.postgres;

import dev.dispatch.core.store.JobStoreException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the {@code jobs} table and its indexes if they are not already there.
 *
 * <p>Deliberately not a migration tool. The DDL is idempotent ({@code CREATE ... IF NOT EXISTS}),
 * and it carries one additive change for tables created before it
 * ({@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS lease_id}). That is as far as an idempotent
 * script should go, and it keeps the dependency list honest. The next schema change is the point
 * to replace this with Flyway or Liquibase pointed at the same SQL.
 *
 * <p>That one change runs only when the column is missing. PostgreSQL takes an ACCESS EXCLUSIVE
 * lock for {@code ADD COLUMN} before it looks at {@code IF NOT EXISTS}, so running it on every
 * startup would queue behind any open transaction on {@code jobs}, such as a backup or a long
 * report, and every claim from every instance would queue behind it in turn. The catalog lookup
 * that comes first takes no lock on the table. Two instances that both find the column missing
 * still race safely: PostgreSQL checks for the column under the lock, so the second waits, then
 * skips, and the added column needs no entry in the tolerated SQL states.
 *
 * <h2>Why {@code IF NOT EXISTS} is not enough on its own</h2>
 * In PostgreSQL, {@code CREATE TABLE IF NOT EXISTS} is <em>not</em> atomic against concurrent DDL.
 * Two instances starting at the same moment both find the table missing, both issue the create;
 * the second blocks on the first's lock and then fails, usually with a unique violation on the
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

    /** The script's additive change: {@code ALTER TABLE <table> ADD COLUMN IF NOT EXISTS <column>}. */
    private static final Pattern ADD_COLUMN = Pattern.compile(
            "ALTER\\s+TABLE\\s+(\\w+)\\s+ADD\\s+COLUMN\\s+IF\\s+NOT\\s+EXISTS\\s+(\\w+)\\b.*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

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
                if (addsAColumnAlreadyThere(connection, sql)) {
                    log.debug("Schema column already present, statement skipped: {}", sql);
                    continue;
                }
                executeToleratingConcurrentCreation(connection, sql);
            }
        } catch (SQLException e) {
            throw new JobStoreException("Failed to initialize the job queue schema", e);
        }
        verifySchemaUsable(dataSource);
        log.info("Job queue schema is present ({} statement(s) applied)", statements.size());
    }

    /**
     * True when {@code sql} is an {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS} for a column the
     * table already has. Reads the catalog through {@link DatabaseMetaData}, which locks nothing.
     */
    private static boolean addsAColumnAlreadyThere(Connection connection, String sql)
            throws SQLException {
        Matcher addColumn = ADD_COLUMN.matcher(sql);
        if (!addColumn.matches()) {
            return false;
        }
        DatabaseMetaData catalog = connection.getMetaData();
        String escape = catalog.getSearchStringEscape();
        try (ResultSet column = catalog.getColumns(connection.getCatalog(),
                pattern(connection.getSchema(), escape),
                pattern(asStored(addColumn.group(1), catalog), escape),
                pattern(asStored(addColumn.group(2), catalog), escape))) {
            return column.next();
        }
    }

    /** An unquoted name the way the catalog stores it: lower case on PostgreSQL, upper on H2. */
    private static String asStored(String name, DatabaseMetaData catalog) throws SQLException {
        if (catalog.storesUpperCaseIdentifiers()) {
            return name.toUpperCase(Locale.ROOT);
        }
        if (catalog.storesLowerCaseIdentifiers()) {
            return name.toLowerCase(Locale.ROOT);
        }
        return name;
    }

    /** A metadata search pattern that matches {@code name} exactly, so {@code _} is not a wildcard. */
    private static String pattern(String name, String escape) {
        if (name == null || escape == null || escape.isEmpty()) {
            return name;
        }
        return name.replace(escape, escape + escape)
                .replace("_", escape + "_")
                .replace("%", escape + "%");
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
     * Confirms the table has every column {@link JdbcJobRows} reads and writes. Without this,
     * swallowing "already exists" errors, or a table made by hand, could hide a missing column and
     * leave the application to discover it on its first claim. It reads no rows.
     */
    private static void verifySchemaUsable(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SELECT " + JdbcJobRows.COLUMNS + " FROM jobs WHERE 1 = 0");
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
     * Drops whole-line {@code --} comments. The statement splitter is intentionally limited. It is
     * only ever fed this one file, which keeps its semicolons out of string literals.
     */
    private static String stripComments(String sql) {
        return sql.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
    }
}
