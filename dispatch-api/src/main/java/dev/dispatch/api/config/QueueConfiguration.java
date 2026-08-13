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
import java.util.UUID;
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

    @Bean
    RetryPolicy retryPolicy(QueueProperties properties) {
        QueueProperties.Retry retry = properties.retry();
        return new ExponentialBackoffRetryPolicy(
                retry.baseDelay(), retry.multiplier(), retry.maxDelay(), retry.jitterFactor());
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    QueueConfig queueConfig(QueueProperties properties) {
        // A blank worker id means "generate one". Two replicas of the same image must not share
        // an id, or they can release each other's leases.
        String workerId = properties.workerId().isBlank()
                ? "instance-" + UUID.randomUUID().toString().substring(0, 8)
                : properties.workerId();
        return QueueConfig.builder()
                .workerId(workerId)
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
