package dev.dispatch.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The REST façade over the queue engine.
 *
 * <p>Spring's job here is narrow on purpose: read configuration, build a {@code JobQueue}, expose
 * HTTP endpoints, and shut the queue down cleanly. Every queue mechanic — claiming, retries,
 * backoff, leases, draining — lives in {@code dispatch-core}, which has no idea Spring exists and can
 * be embedded in a plain {@code main} method just as happily.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class JobQueueApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobQueueApplication.class, args);
    }
}
