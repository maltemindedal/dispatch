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
     * The durable store: PostgreSQL in production, H2 under the dev profile. The same class serves
     * both — see {@code JdbcJobStore} for why the SQL is portable.
     */
    @Bean
    @ConditionalOnProperty(name = "dispatch.store", havingValue = "jdbc", matchIfMissing = true)
    JobStore jdbcJobStore(DataSource dataSource) {
        JobSchema.initialize(dataSource);
        log.info("Job queue backed by JDBC store");
        return new JdbcJobStore(dataSource);
    }

    /**
     * The process-local store. Nothing above this line changes when you switch — which is the
     * point of putting persistence behind an interface.
     */
    @Bean
    @ConditionalOnProperty(name = "dispatch.store", havingValue = "memory")
    JobStore inMemoryJobStore() {
        log.warn("Job queue backed by the IN-MEMORY store: jobs are lost on restart and are not "
                + "shared with other instances");
        return new InMemoryJobStore();
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

    /** Unset retry properties fall back to the policy's own defaults. */
    @Bean
    RetryPolicy retryPolicy(QueueProperties properties) {
        QueueProperties.Retry retry = properties.retry();
        return new ExponentialBackoffRetryPolicy(
                retry.baseDelay() != null
                        ? retry.baseDelay() : ExponentialBackoffRetryPolicy.DEFAULT_BASE_DELAY,
                retry.multiplier() != null
                        ? retry.multiplier() : ExponentialBackoffRetryPolicy.DEFAULT_MULTIPLIER,
                retry.maxDelay() != null
                        ? retry.maxDelay() : ExponentialBackoffRetryPolicy.DEFAULT_MAX_DELAY,
                retry.jitterFactor() != null
                        ? retry.jitterFactor() : ExponentialBackoffRetryPolicy.DEFAULT_JITTER_FACTOR);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Only knobs that are actually set in the properties reach the builder; everything else keeps
     * the engine's default, so those numbers live in exactly one place. A blank worker id means
     * "generate one" — two replicas of the same image must not share an id, or they can release
     * each other's leases — and the builder already mints a unique one.
     */
    @Bean
    QueueConfig queueConfig(QueueProperties properties) {
        QueueConfig.Builder builder = QueueConfig.builder();
        if (!properties.workerId().isBlank()) {
            builder.workerId(properties.workerId());
        }
        if (properties.concurrency() != null) {
            builder.concurrency(properties.concurrency());
        }
        if (properties.claimBatchSize() != null) {
            builder.claimBatchSize(properties.claimBatchSize());
        }
        if (properties.pollInterval() != null) {
            builder.pollInterval(properties.pollInterval());
        }
        if (properties.visibilityTimeout() != null) {
            builder.visibilityTimeout(properties.visibilityTimeout());
        }
        if (properties.maintenanceInterval() != null) {
            builder.maintenanceInterval(properties.maintenanceInterval());
        }
        if (properties.maintenanceBatchSize() != null) {
            builder.maintenanceBatchSize(properties.maintenanceBatchSize());
        }
        if (properties.shutdownDrainTimeout() != null) {
            builder.shutdownDrainTimeout(properties.shutdownDrainTimeout());
        }
        return builder.build();
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
