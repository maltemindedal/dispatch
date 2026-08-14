package dev.dispatch.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dispatch.api.config.QueueProperties.Retry;
import dev.dispatch.api.config.QueueProperties.StoreType;
import dev.dispatch.core.engine.QueueConfig;
import dev.dispatch.core.retry.ExponentialBackoffRetryPolicy;
import dev.dispatch.core.store.memory.InMemoryJobStore;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The property-to-engine hand-over, tested without booting a Spring context: what lands in
 * {@code dispatch.*} must reach the engine, and what is left unset must keep the engine's own
 * defaults.
 */
@DisplayName("Queue configuration wiring")
class QueueConfigurationTest {

    private final QueueConfiguration configuration = new QueueConfiguration();

    @Test
    @DisplayName("set properties reach the engine config")
    void setPropertiesReachTheEngine() {
        QueueProperties properties = new QueueProperties(
                StoreType.MEMORY, "worker-7", 32, 4,
                Duration.ofMillis(100), Duration.ofMinutes(2), Duration.ofSeconds(5), 100,
                Duration.ofSeconds(7), new Retry(null, null, null, null), false);

        QueueConfig config = configuration.queueConfig(properties);

        assertThat(config.workerId()).isEqualTo("worker-7");
        assertThat(config.concurrency()).isEqualTo(32);
        assertThat(config.claimBatchSize()).isEqualTo(4);
        assertThat(config.pollInterval()).isEqualTo(Duration.ofMillis(100));
        assertThat(config.visibilityTimeout()).isEqualTo(Duration.ofMinutes(2));
        assertThat(config.maintenanceInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.maintenanceBatchSize()).isEqualTo(100);
        assertThat(config.shutdownDrainTimeout()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    @DisplayName("unset properties keep the engine's defaults")
    void unsetPropertiesKeepEngineDefaults() {
        QueueProperties properties = new QueueProperties(
                StoreType.MEMORY, "", null, null, null, null, null, null, null,
                new Retry(null, null, null, null), false);

        QueueConfig config = configuration.queueConfig(properties);
        QueueConfig defaults = QueueConfig.defaults();

        assertThat(config.workerId()).as("blank means a generated id").isNotBlank();
        assertThat(config.concurrency()).isEqualTo(defaults.concurrency());
        assertThat(config.claimBatchSize()).isEqualTo(defaults.claimBatchSize());
        assertThat(config.pollInterval()).isEqualTo(defaults.pollInterval());
        assertThat(config.visibilityTimeout()).isEqualTo(defaults.visibilityTimeout());
        assertThat(config.maintenanceInterval()).isEqualTo(defaults.maintenanceInterval());
        assertThat(config.maintenanceBatchSize()).isEqualTo(defaults.maintenanceBatchSize());
        assertThat(config.shutdownDrainTimeout()).isEqualTo(defaults.shutdownDrainTimeout());
    }

    @Test
    @DisplayName("unset retry properties keep the policy's defaults; set ones override")
    void retryPropertiesMapToThePolicy() {
        QueueProperties allUnset = propertiesWithRetry(new Retry(null, null, null, null));
        QueueProperties baseSet = propertiesWithRetry(
                new Retry(Duration.ofMillis(10), 1.0, Duration.ofMillis(10), 0.0));

        var defaulted = (ExponentialBackoffRetryPolicy) configuration.retryPolicy(allUnset);
        var overridden = (ExponentialBackoffRetryPolicy) configuration.retryPolicy(baseSet);

        assertThat(defaulted.ceilingFor(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(overridden.ceilingFor(1)).isEqualTo(Duration.ofMillis(10));
        assertThat(overridden.backoffAfter(5))
                .as("multiplier 1 and zero jitter pin the curve flat")
                .isEqualTo(Duration.ofMillis(10));
    }

    @Test
    @DisplayName("the memory store is selected without ever touching the DataSource")
    void memoryStoreLeavesDataSourceAlone() {
        ObjectProvider<DataSource> untouchable = new ObjectProvider<>() {
            @Override
            public DataSource getObject() {
                throw new AssertionError("the memory store must not resolve a DataSource");
            }
        };

        assertThat(configuration.jobStore(propertiesWithRetry(new Retry(null, null, null, null)),
                untouchable)).isInstanceOf(InMemoryJobStore.class);
    }

    private static QueueProperties propertiesWithRetry(Retry retry) {
        return new QueueProperties(StoreType.MEMORY, "", null, null, null, null, null, null, null,
                retry, false);
    }
}
