package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The builder owns "unset means default": null (and a blank worker id) keeps the engine's own
 * value, so adapters can hand configuration over without translating it knob by knob.
 */
@DisplayName("Queue config")
class QueueConfigTest {

    @Test
    @DisplayName("null and blank inputs keep every default")
    void nullsKeepDefaults() {
        QueueConfig defaults = QueueConfig.defaults();

        QueueConfig config = QueueConfig.builder()
                .workerId(null)
                .workerId("   ")
                .concurrency(null)
                .claimBatchSize(null)
                .pollInterval(null)
                .visibilityTimeout(null)
                .maintenanceInterval(null)
                .maintenanceBatchSize(null)
                .shutdownDrainTimeout(null)
                .build();

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
    @DisplayName("set values win over defaults")
    void setValuesWin() {
        QueueConfig config = QueueConfig.builder()
                .workerId("w-1")
                .concurrency(3)
                .claimBatchSize(2)
                .pollInterval(Duration.ofMillis(50))
                .visibilityTimeout(Duration.ofSeconds(30))
                .maintenanceInterval(Duration.ofMillis(200))
                .maintenanceBatchSize(25)
                .shutdownDrainTimeout(Duration.ofSeconds(3))
                .build();

        assertThat(config).isEqualTo(new QueueConfig("w-1", 3, 2, Duration.ofMillis(50),
                Duration.ofSeconds(30), Duration.ofMillis(200), 25, Duration.ofSeconds(3)));
    }

    @Test
    @DisplayName("generated worker ids are unique per builder")
    void generatedWorkerIdsAreUnique() {
        assertThat(QueueConfig.builder().build().workerId())
                .isNotEqualTo(QueueConfig.builder().build().workerId());
    }

    @Test
    @DisplayName("every validation branch rejects its bad value")
    void validationRejectsBadValues() {
        QueueConfig ok = QueueConfig.defaults();

        assertThatThrownBy(() -> withWorkerId(ok, "   "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("workerId");
        assertThatThrownBy(() -> withWorkerId(ok, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> withConcurrency(ok, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("concurrency");
        assertThatThrownBy(() -> withClaimBatchSize(ok, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("claimBatchSize");
        assertThatThrownBy(() -> withMaintenanceBatchSize(ok, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maintenanceBatchSize");
        assertThatThrownBy(() -> withPollInterval(ok, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pollInterval");
        assertThatThrownBy(() -> withVisibilityTimeout(ok, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("visibilityTimeout");
        assertThatThrownBy(() -> withMaintenanceInterval(ok, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maintenanceInterval");
        assertThatThrownBy(() -> withShutdownDrainTimeout(ok, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shutdownDrainTimeout");
    }

    @Test
    @DisplayName("a zero drain timeout is allowed: shut down without waiting")
    void zeroDrainTimeoutIsAllowed() {
        assertThat(withShutdownDrainTimeout(QueueConfig.defaults(), Duration.ZERO)
                .shutdownDrainTimeout()).isEqualTo(Duration.ZERO);
    }

    // The builder skips nulls by design, so validation is pinned through the record constructor.

    private static QueueConfig withWorkerId(QueueConfig base, String workerId) {
        return new QueueConfig(workerId, base.concurrency(), base.claimBatchSize(),
                base.pollInterval(), base.visibilityTimeout(), base.maintenanceInterval(),
                base.maintenanceBatchSize(), base.shutdownDrainTimeout());
    }

    private static QueueConfig withConcurrency(QueueConfig base, int concurrency) {
        return new QueueConfig(base.workerId(), concurrency, base.claimBatchSize(),
                base.pollInterval(), base.visibilityTimeout(), base.maintenanceInterval(),
                base.maintenanceBatchSize(), base.shutdownDrainTimeout());
    }

    private static QueueConfig withClaimBatchSize(QueueConfig base, int claimBatchSize) {
        return new QueueConfig(base.workerId(), base.concurrency(), claimBatchSize,
                base.pollInterval(), base.visibilityTimeout(), base.maintenanceInterval(),
                base.maintenanceBatchSize(), base.shutdownDrainTimeout());
    }

    private static QueueConfig withPollInterval(QueueConfig base, Duration pollInterval) {
        return new QueueConfig(base.workerId(), base.concurrency(), base.claimBatchSize(),
                pollInterval, base.visibilityTimeout(), base.maintenanceInterval(),
                base.maintenanceBatchSize(), base.shutdownDrainTimeout());
    }

    private static QueueConfig withVisibilityTimeout(QueueConfig base, Duration visibilityTimeout) {
        return new QueueConfig(base.workerId(), base.concurrency(), base.claimBatchSize(),
                base.pollInterval(), visibilityTimeout, base.maintenanceInterval(),
                base.maintenanceBatchSize(), base.shutdownDrainTimeout());
    }

    private static QueueConfig withMaintenanceInterval(QueueConfig base, Duration interval) {
        return new QueueConfig(base.workerId(), base.concurrency(), base.claimBatchSize(),
                base.pollInterval(), base.visibilityTimeout(), interval,
                base.maintenanceBatchSize(), base.shutdownDrainTimeout());
    }

    private static QueueConfig withMaintenanceBatchSize(QueueConfig base, int batchSize) {
        return new QueueConfig(base.workerId(), base.concurrency(), base.claimBatchSize(),
                base.pollInterval(), base.visibilityTimeout(), base.maintenanceInterval(),
                batchSize, base.shutdownDrainTimeout());
    }

    private static QueueConfig withShutdownDrainTimeout(QueueConfig base, Duration timeout) {
        return new QueueConfig(base.workerId(), base.concurrency(), base.claimBatchSize(),
                base.pollInterval(), base.visibilityTimeout(), base.maintenanceInterval(),
                base.maintenanceBatchSize(), timeout);
    }
}
