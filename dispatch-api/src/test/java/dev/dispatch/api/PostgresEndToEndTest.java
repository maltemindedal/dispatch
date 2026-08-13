package dev.dispatch.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.dispatch.api.web.dto.JobResponse;
import dev.dispatch.api.web.dto.StatsResponse;
import dev.dispatch.core.job.JobState;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The whole stack over real HTTP against real PostgreSQL: submit a job through the API, let the
 * virtual-thread workers run it, and read the result back through the API.
 *
 * <p>{@link JobApiTest} covers status codes and payload shapes against the in-memory store; this
 * one exists to prove the pieces are actually connected — schema creation, JDBC claiming,
 * execution, and the stats endpoint reading from shared storage.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "dispatch.store=jdbc",
        "dispatch.demo-handlers=false",
        "dispatch.concurrency=4",
        "dispatch.poll-interval=25ms",
        "dispatch.maintenance-interval=50ms",
        "dispatch.retry.base-delay=10ms",
        "dispatch.retry.max-delay=10ms",
        "dispatch.retry.jitter-factor=0"
})
// Activating the real profile means application-postgres.yml is loaded and bound, not just the
// container's connection details. That is deliberate: a property this file gets wrong (a Hikari
// setting written as a Duration, say) should fail here rather than at the first real startup.
@ActiveProfiles("postgres")
@Import(TestHandlers.class)
@DisplayName("End to end on PostgreSQL")
class PostgresEndToEndTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
                    .withDatabaseName("dispatch")
                    .withUsername("dispatch")
                    .withPassword("dispatch");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("a submitted job is picked up, executed and reported as COMPLETED")
    void submittedJobRunsToCompletion() {
        Map<String, Object> request = Map.of(
                "type", TestHandlers.OK,
                "payload", Map.of("to", "someone@example.com"),
                "priority", 5);

        ResponseEntity<JobResponse> created =
                rest.postForEntity("/jobs", request, JobResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JobResponse job = created.getBody();
        assertThat(job).isNotNull();
        assertThat(job.state()).isEqualTo(JobState.PENDING);
        assertThat(job.payload().get("to").asText()).isEqualTo("someone@example.com");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            JobResponse current = rest.getForObject("/jobs/" + job.id(), JobResponse.class);
            assertThat(current.state()).isEqualTo(JobState.COMPLETED);
            assertThat(current.attempt()).isEqualTo(1);
            // The payload survived the round trip through a TEXT column unchanged.
            assertThat(current.payload().get("to").asText()).isEqualTo("someone@example.com");
        });
    }

    @Test
    @DisplayName("a failing job retries, dies, and can be revived through the API")
    void failingJobDiesAndIsRevived() {
        Map<String, Object> request = Map.of(
                "type", TestHandlers.FAIL,
                "payload", Map.of(),
                "maxRetries", 2);

        JobResponse job = rest.postForObject("/jobs", request, JobResponse.class);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            JobResponse current = rest.getForObject("/jobs/" + job.id(), JobResponse.class);
            assertThat(current.state()).isEqualTo(JobState.DEAD);
            // One attempt plus two retries, and the reason is on the record.
            assertThat(current.attempt()).isEqualTo(3);
            assertThat(current.lastError()).contains("deliberate test failure");
        });

        ResponseEntity<JobResponse> revived =
                rest.postForEntity("/jobs/" + job.id() + "/retry", null, JobResponse.class);
        assertThat(revived.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revived.getBody().attempt()).isZero();

        // It fails again, of course — the handler never recovers. The point is that the operator
        // action took effect against the shared database.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(rest.getForObject("/jobs/" + job.id(), JobResponse.class).attempt())
                        .isPositive());
    }

    @Test
    @DisplayName("a delayed job stays SCHEDULED until its time, and can be cancelled meanwhile")
    void delayedJobCanBeCancelled() {
        Map<String, Object> request = Map.of(
                "type", TestHandlers.OK,
                "payload", Map.of(),
                "scheduledAt", Instant.now().plus(Duration.ofHours(1)).toString());

        JobResponse job = rest.postForObject("/jobs", request, JobResponse.class);
        assertThat(job.state()).isEqualTo(JobState.SCHEDULED);

        ResponseEntity<Void> cancelled =
                rest.exchange("/jobs/" + job.id(), org.springframework.http.HttpMethod.DELETE,
                        null, Void.class);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> afterCancel =
                rest.getForEntity("/jobs/" + job.id(), String.class);
        assertThat(afterCancel.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /stats reads queue depth from the database")
    void statsReflectTheDatabase() {
        rest.postForObject("/jobs", Map.of(
                "type", TestHandlers.OK,
                "payload", Map.of(),
                "scheduledAt", Instant.now().plus(Duration.ofHours(2)).toString()),
                JobResponse.class);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            StatsResponse stats = rest.getForObject("/stats", StatsResponse.class);
            assertThat(stats.workerId()).isNotBlank();
            assertThat(stats.queueDepth()).containsKeys("PENDING", "SCHEDULED", "RUNNING",
                    "COMPLETED", "FAILED", "DEAD");
            assertThat(stats.queueDepth().get("SCHEDULED")).isPositive();
            assertThat(stats.totalJobs()).isPositive();
            assertThat(stats.thisInstance().submitted()).isPositive();
        });
    }
}
