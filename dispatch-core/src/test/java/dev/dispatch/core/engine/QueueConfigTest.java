package dev.dispatch.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

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
}
