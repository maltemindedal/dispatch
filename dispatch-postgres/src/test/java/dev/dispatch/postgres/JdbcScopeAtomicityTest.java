package dev.dispatch.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.store.JobStoreException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The JDBC adapter's exclusive scope is all or nothing, and reports the failure that matters.
 *
 * <p>{@code JobRows} promises that if the work throws, nothing it wrote is visible. The scope
 * used to roll back only for a {@link RuntimeException}; an {@link Error} left the transaction
 * open, and restoring auto-commit in the {@code finally} block then <em>committed</em> it, since
 * switching auto-commit on inside a transaction commits it (the JDBC contract). The block also let
 * a failure while restoring auto-commit, or a failing rollback, replace the failure that caused
 * them.
 *
 * <p>Run against every database the adapter supports; the in-memory adapter is documented as not
 * rolling back and is not part of this.
 */
abstract class JdbcScopeAtomicityTest {

    /** A pooled data source over an empty, schema-initialised database. */
    protected abstract DataSource dataSource();

    private static Job newJob() {
        return Job.newJob(UUID.randomUUID(), JobSubmission.of("send-email", "{}"), Instant.now());
    }

    private boolean visible(JdbcJobRows rows, Job job) {
        Optional<Job> found = rows.inExclusiveScope(scope -> scope.read(job.id()));
        return found.isPresent();
    }

    @Test
    @DisplayName("a RuntimeException inside the scope rolls the write back")
    void runtimeExceptionRollsBack() {
        JdbcJobRows rows = new JdbcJobRows(dataSource());
        Job job = newJob();

        Throwable thrown = catchThrowable(() -> rows.inExclusiveScope(scope -> {
            scope.insert(job);
            throw new IllegalStateException("boom");
        }));

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("boom");
        assertThat(visible(rows, job)).isFalse();
    }

    @Test
    @DisplayName("an Error inside the scope rolls the write back too, instead of committing it")
    void errorRollsBack() {
        JdbcJobRows rows = new JdbcJobRows(dataSource());
        Job job = newJob();

        Throwable thrown = catchThrowable(() -> rows.inExclusiveScope(scope -> {
            scope.insert(job);
            throw new AssertionError("an Error after the write, before the scope finished");
        }));

        assertThat(thrown).isInstanceOf(AssertionError.class);
        assertThat(visible(rows, job)).isFalse();
    }

    @Test
    @DisplayName("a failed commit is reported as the cause, with later failures suppressed under it")
    void failedCommitIsNotMaskedBySecondaryFailures() {
        JdbcJobRows rows = new JdbcJobRows(connectionsThatFail("commit", "setAutoCommit(true)"));

        Throwable thrown = catchThrowable(() -> rows.inExclusiveScope(scope -> {
            scope.insert(newJob());
            return null;
        }));

        assertThat(thrown).isInstanceOf(JobStoreException.class)
                .hasMessageContaining("commit failed");
        assertThat(thrown.getCause().getSuppressed())
                .extracting(Throwable::getMessage)
                .containsExactly("setAutoCommit(true) failed");
    }

    @Test
    @DisplayName("a failing rollback does not replace the exception that made it necessary")
    void failedRollbackKeepsTheOriginalFailure() {
        JdbcJobRows rows = new JdbcJobRows(connectionsThatFail("rollback"));

        Throwable thrown = catchThrowable(() -> rows.inExclusiveScope(scope -> {
            scope.insert(newJob());
            throw new IllegalStateException("boom");
        }));

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("boom");
        assertThat(thrown.getSuppressed()).extracting(Throwable::getMessage)
                .containsExactly("rollback failed");
    }

    /**
     * Data source whose connections throw a {@link SQLException} from the named calls
     * ({@code "commit"}, {@code "rollback"}, {@code "setAutoCommit(true)"}) and otherwise behave.
     * The message is the call's name plus " failed".
     */
    private DataSource connectionsThatFail(String... failingCalls) {
        DataSource real = dataSource();
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(real, method, args);
                    if (!method.getName().equals("getConnection")) {
                        return result;
                    }
                    Connection connection = (Connection) result;
                    return Proxy.newProxyInstance(
                            Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                            (p, call, callArgs) -> {
                                String name = call.getName().equals("setAutoCommit")
                                        && Boolean.TRUE.equals(callArgs[0])
                                        ? "setAutoCommit(true)" : call.getName();
                                for (String failing : failingCalls) {
                                    if (failing.equals(name)) {
                                        throw new SQLException(name + " failed");
                                    }
                                }
                                return invoke(connection, call, callArgs);
                            });
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
