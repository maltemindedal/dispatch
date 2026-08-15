package dev.dispatch.core.store;

import dev.dispatch.core.testing.JobStoreContract;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;

/**
 * Runs the shared {@link JobStoreContract} against the in-memory store. The JDBC store in
 * dispatch-postgres runs the very same suite, which is how "swappable implementations" stops being a
 * claim and starts being a test result.
 */
@DisplayName("JobStore")
class InMemoryJobStoreTest extends JobStoreContract {

    @Override
    protected JobStore createStore() {
        return JobStore.inMemory();
    }

    @Override
    protected JobStore createStore(Supplier<UUID> ids) {
        return JobStore.inMemory(ids);
    }
}
