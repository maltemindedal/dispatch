package dev.dispatch.api.config;

import dev.dispatch.core.demo.ResizeImageJobHandler;
import dev.dispatch.core.demo.SendEmailJobHandler;
import dev.dispatch.core.engine.JobQueue;
import dev.dispatch.core.engine.QueueConfig;
import dev.dispatch.core.handler.InMemoryJobHandlerRegistry;
import dev.dispatch.core.handler.JobHandlerRegistry;
import dev.dispatch.core.retry.ExponentialBackoffRetryPolicy;
import dev.dispatch.core.retry.RetryPolicy;
import dev.dispatch.core.store.JobStore;
import dev.dispatch.core.store.memory.InMemoryJobStore;
import dev.dispatch.postgres.JdbcJobStore;
import dev.dispatch.postgres.JobSchema;
import java.time.Clock;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the engine into the application context.
 *
 * <p>Note what is <em>not</em> here: no scheduling, no thread pools, no transaction management, no
 * repositories. The engine brings all of that. This class picks a store, collects the handlers and
 * hands over a config object.
 */
@Configuration(proxyBeanMethods = false)
public class QueueConfiguration {

    private static final Logger log = LoggerFactory.getLogger(QueueConfiguration.class);

    /**
     * Picks the adapter for the store seam from the one typed {@code dispatch.store} property.
     * A typo fails at property binding with the valid values listed, not at bean resolution.
     *
     * <p>The JDBC store serves PostgreSQL in production and H2 under the dev profile alike — see
     * {@code JdbcJobStore} for why the SQL is portable. The DataSource is resolved lazily so the
     * in-memory choice never touches it.
     */
    @Bean
    JobStore jobStore(QueueProperties properties, ObjectProvider<DataSource> dataSource) {
        return switch (properties.store()) {
            case JDBC -> {
                DataSource source = dataSource.getObject();
                JobSchema.initialize(source);
                log.info("Job queue backed by JDBC store");
                yield new JdbcJobStore(source);
            }
            case MEMORY -> {
                log.warn("Job queue backed by the IN-MEMORY store: jobs are lost on restart and "
                        + "are not shared with other instances");
                yield new InMemoryJobStore();
            }
        };
    }

    /**
     * Collects every {@link JobHandlerRegistration} bean. {@code ObjectProvider} rather than a
     * {@code List} parameter so an application with no handlers yet still starts.
     */
    @Bean
    JobHandlerRegistry jobHandlerRegistry(ObjectProvider<JobHandlerRegistration> registrations) {
        InMemoryJobHandlerRegistry registry = new InMemoryJobHandlerRegistry();
        registrations.orderedStream().forEach(registration ->
                registry.register(registration.type(), registration.handler()));
        log.info("Registered job handlers: {}", registry.registeredTypes());
        return registry;
    }

    /** Unset retry properties keep the policy's own defaults; {@code of} owns that rule. */
    @Bean
    RetryPolicy retryPolicy(QueueProperties properties) {
        QueueProperties.Retry retry = properties.retry();
        return ExponentialBackoffRetryPolicy.of(
                retry.baseDelay(), retry.multiplier(), retry.maxDelay(), retry.jitterFactor());
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * A straight hand-over: the builder itself treats null (and a blank worker id) as "keep the
     * engine's default", so the defaults live in exactly one place — {@code QueueConfig.Builder}.
     */
    @Bean
    QueueConfig queueConfig(QueueProperties properties) {
        return QueueConfig.builder()
                .workerId(properties.workerId())
                .concurrency(properties.concurrency())
                .claimBatchSize(properties.claimBatchSize())
                .pollInterval(properties.pollInterval())
                .visibilityTimeout(properties.visibilityTimeout())
                .maintenanceInterval(properties.maintenanceInterval())
                .maintenanceBatchSize(properties.maintenanceBatchSize())
                .shutdownDrainTimeout(properties.shutdownDrainTimeout())
                .build();
    }

    /**
     * The queue itself.
     *
     * <p>{@code destroyMethod = "close"} is what satisfies the graceful-shutdown requirement: on
     * SIGTERM Spring stops accepting HTTP requests (see {@code server.shutdown: graceful}), then
     * destroys this bean, which stops claiming and drains in-flight jobs within the configured
     * deadline. Anything still running past that deadline is interrupted and lands back on the
     * queue for another instance.
     */
    @Bean(destroyMethod = "close")
    JobQueue jobQueue(
            JobStore store,
            JobHandlerRegistry registry,
            RetryPolicy retryPolicy,
            QueueConfig config,
            Clock clock) {
        return JobQueue.builder()
                .store(store)
                .registry(registry)
                .retryPolicy(retryPolicy)
                .config(config)
                .clock(clock)
                .build()
                .start();
    }

    // ------------------------------------------------------------ demo handlers

    @Bean
    @ConditionalOnProperty(name = "dispatch.demo-handlers", havingValue = "true",
            matchIfMissing = true)
    JobHandlerRegistration sendEmailHandler() {
        return new JobHandlerRegistration(SendEmailJobHandler.TYPE, new SendEmailJobHandler());
    }

    @Bean
    @ConditionalOnProperty(name = "dispatch.demo-handlers", havingValue = "true",
            matchIfMissing = true)
    JobHandlerRegistration resizeImageHandler() {
        return new JobHandlerRegistration(ResizeImageJobHandler.TYPE, new ResizeImageJobHandler());
    }
}
