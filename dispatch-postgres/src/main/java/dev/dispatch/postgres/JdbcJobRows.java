package dev.dispatch.postgres;

import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.store.JobField;
import dev.dispatch.core.store.JobFilter;
import dev.dispatch.core.store.JobRows;
import dev.dispatch.core.store.JobSelection;
import dev.dispatch.core.store.JobStore;
import dev.dispatch.core.store.JobStoreException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * Job rows in a SQL database, so several application instances can share one queue.
 *
 * <h2>How claiming stays exclusive</h2>
 * Every exclusive scope is one transaction, and the statement that makes the design work is the one
 * behind {@link Scope#matching}:
 *
 * <pre>{@code
 * SELECT ... FROM jobs
 *  WHERE state IN (...) AND scheduled_at <= ?
 *  ORDER BY priority DESC, scheduled_at, created_at, id
 *  LIMIT ?
 *  FOR UPDATE SKIP LOCKED
 * }</pre>
 *
 * <p>{@code FOR UPDATE} takes a row lock on everything the select returns, held until the
 * transaction commits. On its own that would make instances queue up behind each other: instance B
 * would <em>block</em> on the rows instance A is holding. {@code SKIP LOCKED} changes that to
 * "pretend those rows are not there" — so B walks past A's rows and takes the next ones down the
 * ordering.
 *
 * <p>The result is a shared queue with no coordinator, no leader election and no distributed lock:
 * N instances can hammer the same table and each row still goes to exactly one of them. The select
 * and the {@code UPDATE} that marks the rows RUNNING happen in one transaction, so a crash between
 * the two rolls back and the jobs simply stay PENDING.
 *
 * <p>{@link Scope#byId} deliberately omits {@code SKIP LOCKED}: an operator action must wait for the
 * row and answer about the state it is really in, rather than report "not found" because a claimer
 * held it for a moment.
 *
 * <h2>On H2</h2>
 * The same SQL runs on H2 for local development, but H2's locking is coarser than PostgreSQL's: a
 * contending reader gets an empty result rather than the next unlocked rows. Exclusivity — the
 * property that matters — still holds, but throughput under contention does not, which is why the
 * multi-instance tests run against real PostgreSQL via Testcontainers.
 *
 * <h2>Where the rules live</h2>
 * Nowhere in this class. It renders a {@link JobSelection} into a {@code WHERE} and an
 * {@code ORDER BY}, and writes back {@link Job} snapshots someone else transitioned. Which rows are
 * claimable, what order they come in and when an action is refused are all decided once in
 * {@link JobStore}, so there is nothing here that can disagree with the in-memory adapter.
 */
public final class JdbcJobRows implements JobRows {

    private static final String COLUMNS =
            "id, type, payload, priority, max_retries, attempt, state, scheduled_at, "
            + "created_at, updated_at, locked_until, locked_by, last_error";

    private static final String INSERT_SQL =
            "INSERT INTO jobs (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_BY_ID_SQL =
            "SELECT " + COLUMNS + " FROM jobs WHERE id = ?";

    /** No {@code SKIP LOCKED}: an operator action waits for the row rather than missing it. */
    private static final String SELECT_BY_ID_FOR_UPDATE_SQL = SELECT_BY_ID_SQL + " FOR UPDATE";

    /** Only the columns a transition can change; id, type, payload and created_at are immutable. */
    private static final String UPDATE_SQL =
            "UPDATE jobs SET attempt = ?, state = ?, scheduled_at = ?, updated_at = ?,"
            + " locked_until = ?, locked_by = ?, last_error = ? WHERE id = ?";

    private static final String DELETE_BY_ID_SQL = "DELETE FROM jobs WHERE id = ?";

    private final DataSource dataSource;

    /**
     * @param dataSource pooled and owned by the caller; this adapter never closes it
     */
    public JdbcJobRows(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Convenience: apply the schema, then build a store over the same DataSource. */
    public static JobStore createAndInitializeSchema(DataSource dataSource) {
        JobSchema.initialize(dataSource);
        return JobStore.over(new JdbcJobRows(dataSource));
    }

    /**
     * Runs {@code work} in one transaction, committing on success and rolling back on any failure.
     * Explicit transactions are not optional here: {@code FOR UPDATE} row locks live and die with
     * the transaction, so autocommit would release every lock the instant the select returned.
     */
    @Override
    public <R> R inExclusiveScope(Function<Scope, R> work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                R result = work.apply(new TransactionScope(connection));
                connection.commit();
                return result;
            } catch (RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new JobStoreException("Job store operation failed: " + e.getMessage(), e);
        }
    }

    /** The SQL column each orderable field lives in. The schema's business, not the engine's. */
    private static String columnOf(JobField field) {
        return switch (field) {
            case PRIORITY -> "priority";
            case SCHEDULED_AT -> "scheduled_at";
            case CREATED_AT -> "created_at";
            case LOCKED_UNTIL -> "locked_until";
            case ID -> "id";
        };
    }

    private static String orderByClause(List<JobSelection.Order> ordering) {
        return ordering.stream()
                .map(order -> columnOf(order.field()) + (order.descending() ? " DESC" : ""))
                .collect(Collectors.joining(", ", " ORDER BY ", ""));
    }

    private final class TransactionScope implements Scope {

        private final Connection connection;

        private TransactionScope(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void insert(Job job) {
            try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
                statement.setObject(1, job.id());
                statement.setString(2, job.type());
                statement.setString(3, job.payload());
                statement.setInt(4, job.priority());
                statement.setInt(5, job.maxRetries());
                statement.setInt(6, job.attempt());
                statement.setString(7, job.state().name());
                setInstant(statement, 8, job.scheduledAt());
                setInstant(statement, 9, job.createdAt());
                setInstant(statement, 10, job.updatedAt());
                setInstant(statement, 11, job.lockedUntil());
                statement.setString(12, job.lockedBy());
                statement.setString(13, job.lastError());
                statement.executeUpdate();
            } catch (SQLException e) {
                throw failure(e);
            }
        }

        @Override
        public Optional<Job> byId(UUID id) {
            return selectOne(SELECT_BY_ID_FOR_UPDATE_SQL, id);
        }

        @Override
        public Optional<Job> read(UUID id) {
            return selectOne(SELECT_BY_ID_SQL, id);
        }

        private Optional<Job> selectOne(String sql, UUID id) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, id);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(mapRow(resultSet)) : Optional.empty();
                }
            } catch (SQLException e) {
                throw failure(e);
            }
        }

        @Override
        public List<Job> matching(JobSelection selection, Instant now, int limit) {
            String states = selection.states().stream()
                    .map(state -> "'" + state.name() + "'")
                    .collect(Collectors.joining(", "));
            String sql = "SELECT " + COLUMNS + " FROM jobs"
                    + " WHERE state IN (" + states + ") AND " + columnOf(selection.dueBy()) + " <= ?"
                    + orderByClause(selection.ordering())
                    + " LIMIT ?"
                    + " FOR UPDATE SKIP LOCKED";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                setInstant(statement, 1, now);
                statement.setInt(2, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<Job> jobs = new ArrayList<>();
                    while (resultSet.next()) {
                        jobs.add(mapRow(resultSet));
                    }
                    return List.copyOf(jobs);
                }
            } catch (SQLException e) {
                throw failure(e);
            }
        }

        @Override
        public void write(List<Job> updated) {
            if (updated.isEmpty()) {
                return;
            }
            try (PreparedStatement statement = connection.prepareStatement(UPDATE_SQL)) {
                for (Job job : updated) {
                    statement.setInt(1, job.attempt());
                    statement.setString(2, job.state().name());
                    setInstant(statement, 3, job.scheduledAt());
                    setInstant(statement, 4, job.updatedAt());
                    setInstant(statement, 5, job.lockedUntil());
                    statement.setString(6, job.lockedBy());
                    statement.setString(7, job.lastError());
                    statement.setObject(8, job.id());
                    statement.addBatch();
                }
                statement.executeBatch();
            } catch (SQLException e) {
                throw failure(e);
            }
        }

        @Override
        public void delete(UUID id) {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_BY_ID_SQL)) {
                statement.setObject(1, id);
                statement.executeUpdate();
            } catch (SQLException e) {
                throw failure(e);
            }
        }

        @Override
        public List<Job> list(JobFilter filter) {
            StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM jobs");
            List<Object> parameters = new ArrayList<>();
            if (filter.state() != null) {
                sql.append(parameters.isEmpty() ? " WHERE" : " AND").append(" state = ?");
                parameters.add(filter.state().name());
            }
            if (filter.type() != null) {
                sql.append(parameters.isEmpty() ? " WHERE" : " AND").append(" type = ?");
                parameters.add(filter.type());
            }
            sql.append(orderByClause(JobSelection.LISTING_ORDER)).append(" LIMIT ? OFFSET ?");
            parameters.add(filter.limit());
            parameters.add(filter.offset());

            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                for (int i = 0; i < parameters.size(); i++) {
                    statement.setObject(i + 1, parameters.get(i));
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<Job> jobs = new ArrayList<>();
                    while (resultSet.next()) {
                        jobs.add(mapRow(resultSet));
                    }
                    return List.copyOf(jobs);
                }
            } catch (SQLException e) {
                throw failure(e);
            }
        }

        @Override
        public Map<JobState, Long> countsByState() {
            Map<JobState, Long> counts = JobState.zeroCounts();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT state, COUNT(*) FROM jobs GROUP BY state");
                    ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    counts.put(JobState.valueOf(resultSet.getString(1)), resultSet.getLong(2));
                }
            } catch (SQLException e) {
                throw failure(e);
            }
            return counts;
        }

        @Override
        public void deleteAll() {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM jobs")) {
                statement.executeUpdate();
            } catch (SQLException e) {
                throw failure(e);
            }
        }
    }

    private static JobStoreException failure(SQLException e) {
        return new JobStoreException("Job store operation failed: " + e.getMessage(), e);
    }

    private static Job mapRow(ResultSet resultSet) throws SQLException {
        return new Job(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("type"),
                resultSet.getString("payload"),
                resultSet.getInt("priority"),
                resultSet.getInt("max_retries"),
                resultSet.getInt("attempt"),
                JobState.valueOf(resultSet.getString("state")),
                instant(resultSet, "scheduled_at"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"),
                instant(resultSet, "locked_until"),
                resultSet.getString("locked_by"),
                resultSet.getString("last_error"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static void setInstant(PreparedStatement statement, int index, Instant value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            statement.setObject(index, value.atOffset(ZoneOffset.UTC));
        }
    }
}
