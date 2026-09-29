package dev.dispatch.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dispatch.core.engine.JobQueue;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.store.JobStore;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * When the queue starts claiming work. It has to be after the application is up: an instance whose
 * startup fails (a later bean, a port already taken) must not have run anybody's jobs first.
 *
 * <p>Runs a real Spring Boot startup, without the web server, around just the queue wiring.
 * {@code ApplicationRunner}s run after the context is refreshed and before the ready event, which
 * is exactly the window between "the beans exist" and "the application is up".
 */
@DisplayName("Queue startup")
class QueueStartupTest {

    private static final String[] ARGS = {
            "--dispatch.store=memory", "--dispatch.demo-handlers=false",
            "--dispatch.poll-interval=10ms", "--dispatch.maintenance-interval=10ms"};

    /** What the wiring below hands the test, since a failed startup leaves no context to ask. */
    private static final AtomicReference<Observed> LAST = new AtomicReference<>();

    private static final class Observed {
        final JobQueue queue;
        final JobStore store;
        final Job dueNow;
        final AtomicBoolean runningWhenRunnersRan = new AtomicBoolean(true);

        Observed(JobQueue queue, JobStore store) {
            this.queue = queue;
            this.store = store;
            // Nothing handles this type, so it is never completed, but a queue that is claiming
            // would still claim it within a few milliseconds and count that.
            this.dueNow = store.insert(JobSubmission.of("nobody-handles-this", "{}"),
                    Instant.now());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(QueueProperties.class)
    static class Wiring {

        @Bean
        Observed observed(JobQueue queue, JobStore store) {
            Observed observed = new Observed(queue, store);
            LAST.set(observed);
            return observed;
        }

        @Bean
        ApplicationRunner observeAtRunnerTime(Observed observed) {
            return args -> observed.runningWhenRunnersRan.set(observed.queue.isRunning());
        }
    }

    /** The tail of a startup that goes wrong: slow, then failing, after everything else is built. */
    @Configuration(proxyBeanMethods = false)
    static class StartupFails {

        @Bean
        ApplicationRunner failAfterAWhile() {
            return args -> {
                Thread.sleep(300);
                throw new IllegalStateException("port already in use, say");
            };
        }
    }

    @Test
    @DisplayName("the queue is not running while the application starts, and is once it is ready")
    void startsOnceTheApplicationIsReady() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                QueueConfiguration.class, Wiring.class)
                .web(WebApplicationType.NONE).run(ARGS)) {
            Observed observed = context.getBean(Observed.class);

            assertThat(observed.runningWhenRunnersRan)
                    .as("running when the runners ran, before the application was ready")
                    .isFalse();
            assertThat(observed.queue.isRunning()).as("running once ready").isTrue();
        }
    }

    @Test
    @DisplayName("an application that fails to start has claimed nothing")
    void failedStartupClaimsNothing() {
        SpringApplicationBuilder app = new SpringApplicationBuilder(
                QueueConfiguration.class, Wiring.class, StartupFails.class)
                .web(WebApplicationType.NONE);

        assertThatThrownBy(() -> app.run(ARGS)).isInstanceOf(IllegalStateException.class)
                .hasMessage("port already in use, say");

        // A queue that had started with the beans would have had 300 ms here to claim the job.
        Observed observed = LAST.get();
        assertThat(observed.queue.metrics().claimed()).isZero();
        assertThat(observed.store.find(observed.dueNow.id()).orElseThrow().state())
                .isEqualTo(JobState.PENDING);
    }
}
