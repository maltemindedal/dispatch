package dev.dispatch.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Pins the exact JSON of {@code GET /stats}: key order, state order, number formatting.
 *
 * <p>Its own class, and so its own application context, because {@code thisInstance} counts what
 * the process has done since it started. In a context shared with other tests those counters
 * depend on which tests ran first; here nothing has run, so every counter is at its starting
 * value and the whole body can be compared as written.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "dispatch.store=memory",
                // A fixed id makes the body fully deterministic, and differs from every other
                // test's properties so this class gets a context of its own.
                "dispatch.worker-id=stats-contract",
                "dispatch.poll-interval=1h",
                "dispatch.maintenance-interval=1h"
        })
@ActiveProfiles("dev")
@DisplayName("GET /stats wire contract")
class StatsContractTest {

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("an idle instance reports every state at zero and every counter at its start")
    void idleStats() throws IOException, InterruptedException {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/stats"))
                        .timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type")).contains("application/json");
        assertThat(response.body())
                .isEqualTo("{\"workerId\":\"stats-contract\",\"queueDepth\":"
                        + "{\"PENDING\":0,\"SCHEDULED\":0,\"RUNNING\":0,\"COMPLETED\":0,"
                        + "\"FAILED\":0,\"DEAD\":0},\"totalJobs\":0,\"backlog\":0,"
                        + "\"thisInstance\":{\"submitted\":0,\"claimed\":0,\"succeeded\":0,"
                        + "\"failedAttempts\":0,\"retriesScheduled\":0,\"deadLettered\":0,"
                        + "\"leasesReclaimed\":0,\"leasesLost\":0,\"inFlight\":0,"
                        + "\"failureRate\":0.0,\"averageExecutionMs\":0.0}}");
    }
}
