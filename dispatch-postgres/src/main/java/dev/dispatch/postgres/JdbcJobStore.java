package dev.dispatch.postgres;

import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.store.JobFilter;
import dev.dispatch.core.store.JobStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link JobStore} backed by a SQL database, so several application instances can share one
 * queue.
 *
 * <h2>How claiming stays exclusive</h2>
 * The interesting statement is in {@link #claim}:
 *
 * <pre>{@code
 * SELECT ... FROM jobs
 *  WHERE state = 'PENDING' AND scheduled_at <= ?
 *  ORDER BY priority DESC, scheduled_at, created_at
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
 * N instances can hammer the same table and each row still goes to exactly one of them. The claim
 * and the {@code UPDATE} that marks the rows RUNNING happen in a single transaction, so a crash
 * between the two rolls back and the jobs simply stay PENDING.
 *
 * <h2>On H2</h2>
 * The same SQL runs on H2 for local development, but H2's locking is coarser than PostgreSQL's: a
 * contending reader gets an empty result rather than the next unlocked rows. Exclusivity — the
 * property that matters — still holds, but throughput under contention does not, which is why the
 * multi-instance tests run against real PostgreSQL via Testcontainers.
 *
 * <h2>Where the lifecycle rules live</h2>
 * Nowhere in this class. Every write loads the row, applies the same validated transition method
 * the in-memory store uses, and writes the result back, so the state machine is enforced once
 * rather than re-implemented in SQL.
 */
public final class JdbcJobStore implements JobStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcJobStore.class);

    private static final String COLUMNS =
            "id, type, payload, priority, max_retries, attempt, state, scheduled_at, "
            + "created_at, updated_at, locked_until, locked_by, last_error";

    private static final String INSERT_SQL =
            "INSERT INTO jobs (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_BY_ID_SQL =
            "SELECT " + COLUMNS + " FROM jobs WHERE id = ?";

    private static final String SELECT_BY_ID_FOR_UPDATE_SQL =
            SELECT_BY_ID_SQL + " FOR UPDATE";

    /** The claim query. See the class javadoc — this line is the whole design. */
    private static final String SELECT_CLAIMABLE_SQL =
            "SELECT " + COLUMNS + " FROM jobs"
            + " WHERE state = 'PENDING' AND scheduled_at <= ?"
            + " ORDER BY priority DESC, scheduled_at, created_at"
            + " LIMIT ?"
            + " FOR UPDATE SKIP LOCKED";

    private static final String SELECT_DUE_SQL =
            "SELECT " + COLUMNS + " FROM jobs"
            + " WHERE state IN ('SCHEDULED', 'FAILED') AND scheduled_at <= ?"
            + " ORDER BY priority DESC, scheduled_at, created_at"
            + " LIMIT ?"
            + " FOR UPDATE SKIP LOCKED";

    private static final String SELECT_EXPIRED_LEASES_SQL =
            "SELECT " + COLUMNS + " FROM jobs"
            + " WHERE state = 'RUNNING' AND locked_until <= ?"
            + " ORDER BY locked_until"
            + " LIMIT ?"
            + " FOR UPDATE SKIP LOCKED";

    /** Only the columns a transition can change; id, type, payload and created_at are immutable. */
    private static final String UPDATE_SQL =
            "UPDATE jobs SET attempt = ?, state = ?, scheduled_at = ?, updated_at = ?,"
            + " locked_until = ?, locked_by = ?, last_error = ? WHERE id = ?";

    private static final String DELETE_CANCELLABLE_SQL =
            "DELETE FROM jobs WHERE id = ? AND state IN ('PENDING', 'SCHEDULED')";

    private final DataSource dataSource;

    /**
     * @param dataSource pooled and owned by the caller; this store never closes it
     */
    public JdbcJobStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Convenience: apply the schema, then build a store over the same DataSource. */
    public static JdbcJobStore createAndInitializeSchema(DataSource dataSource) {
        JobSchema.initialize(dataSource);
        return new JdbcJobStore(dataSource);
    }

    // ---------------------------------------------------------------- writing

    @Override
    public Job insert(JobSubmission submission, Instant now) {
        Job job = Job.newJob(UUID.randomUUID(), submission, now);
        return inTransaction(connection -> {
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
            }
            return job;
        });
    }

    @Override
    public List<Job> claim(String workerId, int limit, Duration visibilityTimeout, Instant now) {
        Objects.requireNonNull(workerId, "workerId");
        if (limit <= 0) {
            return List.of();
        }
        return inTransaction(connection -> {
            List<Job> candidates = selectBatch(connection, SELECT_CLAIMABLE_SQL, now, limit);
            if (candidates.isEmpty()) {
                return List.of();
            }
            // The rows are locked until this transaction commits, so no other instance can be
            // looking at them; the transition itself is the ordinary domain one.
            List<Job> claimed = candidates.stream()
                    .map(job -> job.claimedBy(workerId, now, visibilityTimeout))
                    .toList();
            applyUpdates(connection, claimed);
            return claimed;
        });
    }

    @Override
    public Optional<Job> complete(UUID id, String workerId, Instant now) {
        return transitionLeasedJob(id, workerId, job -> job.completed(now));
    }

    @Override
    public Optional<Job> fail(UUID id, String workerId, String error, Instant retryAt, Instant now) {
        return transitionLeasedJob(id, workerId, job -> job.failedWithRetryAt(retryAt, error, now));
    }

    @Override
    public Optional<Job> deadLetter(UUID id, String workerId, String error, Instant now) {
        return transitionLeasedJob(id, workerId, job -> job.deadLettered(error, now));
    }

    @Override
    public int promoteDueJobs(Instant now, int limit) {
        return sweep(SELECT_DUE_SQL, now, limit, job -> job.promotedToPending(now));
    }

    @Override
    public int reclaimExpiredLeases(Instant now, int limit) {
        int reclaimed = sweep(SELECT_EXPIRED_LEASES_SQL, now, limit, job -> job.leaseExpired(now));
        if (reclaimed > 0) {
            log.debug("Reclaimed {} expired lease(s)", reclaimed);
        }
        return reclaimed;
    }

    @Override
    public boolean cancel(UUID id) {
        // A single conditional DELETE: the state predicate is the guard, so there is no window
        // between checking and deleting for a worker to claim the job in.
        return inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(DELETE_CANCELLABLE_SQL)) {
                statement.setObject(1, id);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public Optional<Job> requeueDeadJob(UUID id, Instant now) {
        return inTransaction(connection -> {
            Optional<Job> existing = selectForUpdate(connection, id);
            if (existing.isEmpty() || existing.get().state() != JobState.DEAD) {
                return Optional.empty();
            }
            Job revived = existing.get().revivedForManualRetry(now);
            applyUpdates(connection, List.of(revived));
            return Optional.of(revived);
        });
    }

    // ---------------------------------------------------------------- reading

    @Override
    public Optional<Job> find(UUID id) {
        return inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_BY_ID_SQL)) {
                statement.setObject(1, id);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(mapRow(resultSet)) : Optional.<Job>empty();
                }
            }
        });
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
        // Newest first, with id as a tiebreak so paging cannot repeat or skip a row. Matches the
        // in-memory store's ordering exactly.
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
        parameters.add(filter.limit());
        parameters.add(filter.offset());

        return inTransaction(connection -> {
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
            }
        });
    }

    @Override
    public Map<JobState, Long> countsByState() {
        return inTransaction(connection -> {
            Map<JobState, Long> counts = new EnumMap<>(JobState.class);
            for (JobState state : JobState.values()) {
                counts.put(state, 0L);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT state, COUNT(*) FROM jobs GROUP BY state");
                    ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    counts.put(JobState.valueOf(resultSet.getString(1)), resultSet.getLong(2));
                }
            }
            return counts;
        });
    }

    @Override
    public long count() {
        return inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM jobs");
                    ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        });
    }

    /** Removes every row. Intended for tests and local resets. */
    public void deleteAll() {
        inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM jobs")) {
                return statement.executeUpdate();
            }
        });
    }

    // ---------------------------------------------------------------- internals

    /**
     * Loads a job under a row lock, checks the caller still owns the lease, applies the transition
     * and writes it back — all inside one transaction.
     */
    private Optional<Job> transitionLeasedJob(UUID id, String workerId, UnaryOperator<Job> transition) {
        Objects.requireNonNull(workerId, "workerId");
        return inTransaction(connection -> {
            Optional<Job> existing = selectForUpdate(connection, id);
            if (existing.isEmpty()) {
                return Optional.empty();
            }
            Job job = existing.get();
            // Refuse writes from a worker whose lease was reclaimed while it was still running.
            if (job.state() != JobState.RUNNING || !workerId.equals(job.lockedBy())) {
                return Optional.empty();
            }
            Job updated = transition.apply(job);
            applyUpdates(connection, List.of(updated));
            return Optional.of(updated);
        });
    }

    /** Shared shape of the two maintenance sweeps. */
    private int sweep(String selectSql, Instant now, int limit, UnaryOperator<Job> transition) {
        if (limit <= 0) {
            return 0;
        }
        return inTransaction(connection -> {
            List<Job> candidates = selectBatch(connection, selectSql, now, limit);
            if (candidates.isEmpty()) {
                return 0;
            }
            applyUpdates(connection, candidates.stream().map(transition).toList());
            return candidates.size();
        });
    }

    private List<Job> selectBatch(Connection connection, String sql, Instant now, int limit)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            setInstant(statement, 1, now);
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Job> jobs = new ArrayList<>();
                while (resultSet.next()) {
                    jobs.add(mapRow(resultSet));
                }
                return jobs;
            }
        }
    }

    private Optional<Job> selectForUpdate(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(SELECT_BY_ID_FOR_UPDATE_SQL)) {
            statement.setObject(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapRow(resultSet)) : Optional.empty();
            }
        }
    }

    private void applyUpdates(Connection connection, List<Job> jobs) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPDATE_SQL)) {
            for (Job job : jobs) {
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
        }
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

    /**
     * Runs {@code work} in one transaction, committing on success and rolling back on any failure.
     * Explicit transactions are not optional here: {@code FOR UPDATE} row locks live and die with
     * the transaction, so autocommit would release every lock the instant the select returned.
     */
    private <T> T inTransaction(SqlFunction<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new JobStoreException("Job store operation failed: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }
}
