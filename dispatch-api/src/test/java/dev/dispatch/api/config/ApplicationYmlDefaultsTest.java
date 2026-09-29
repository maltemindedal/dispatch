package dev.dispatch.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dispatch.core.engine.QueueConfig;
import dev.dispatch.core.retry.ExponentialBackoffRetryPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@code application.yml} restates every engine default, and because each value is bound it
 * overrides the engine's own: under Spring the defaults in {@link QueueConfig} never apply. That is
 * deliberate (the file is where an operator reads what the values are), but it is two copies of
 * the same numbers, and {@link QueueProperties} warns that a second copy drifts.
 *
 * <p>This binds the shipped file the way the application does and compares the result with the
 * engine's own defaults, so changing one side without the other fails here instead of silently
 * shipping a different default than the engine documents.
 */
@DisplayName("application.yml versus the engine defaults")
class ApplicationYmlDefaultsTest {

    @EnableConfigurationProperties(QueueProperties.class)
    static class Properties {
    }

    @Test
    @DisplayName("the shipped file yields exactly the defaults the engine would use on its own")
    void ymlRestatesTheEngineDefaults() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Properties.class)
                .run(context -> {
                    QueueProperties properties = context.getBean(QueueProperties.class);
                    QueueConfiguration wiring = new QueueConfiguration();

                    // The worker id is generated per process, so it is the one field that differs.
                    assertThat(wiring.queueConfig(properties))
                            .usingRecursiveComparison()
                            .ignoringFields("workerId")
                            .isEqualTo(QueueConfig.defaults());

                    ExponentialBackoffRetryPolicy fromYml =
                            (ExponentialBackoffRetryPolicy) wiring.retryPolicy(properties);
                    ExponentialBackoffRetryPolicy engine = ExponentialBackoffRetryPolicy.defaults();
                    for (int attempt = 1; attempt <= 12; attempt++) {
                        assertThat(fromYml.ceilingFor(attempt))
                                .as("backoff ceiling for attempt %d", attempt)
                                .isEqualTo(engine.ceilingFor(attempt));
                    }
                    assertThat(fromYml).hasToString(engine.toString());

                    // Not an engine setting, so nothing to compare with: this pins the 1 MiB that
                    // docs/reference/configuration.md promises.
                    assertThat(properties.maxPayloadBytes()).isEqualTo(1024 * 1024);
                });
    }
}
