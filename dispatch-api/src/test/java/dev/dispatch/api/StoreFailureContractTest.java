package dev.dispatch.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dispatch.core.store.JobRows;
import dev.dispatch.core.store.JobStore;
import dev.dispatch.core.store.JobStoreException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

/**
 * What a client sees when the job store itself fails: a fixed problem document, never the driver's
 * or the pool's own words.
 *
 * <p>The store here always throws a {@link JobStoreException} whose message stands in for what a
 * real adapter puts there ("HikariPool-1 - Connection is not available ...", SQL error text with
 * parameter values, hostnames). It needs a real HTTP round trip because the path that used to leak
 * is Boot's error controller, which MockMvc does not render.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "dispatch.store=memory",
                "dispatch.poll-interval=1h",
                "dispatch.maintenance-interval=1h"
        })
@ActiveProfiles("dev")
@DisplayName("Store failures over HTTP")
class StoreFailureContractTest {

    private static final String SECRET =
            "Job store operation failed: HikariPool-1 - Connection is not available "
            + "(jdbc:postgresql://db.internal:5432/dispatch, user dispatch)";

    @TestConfiguration
    static class FailingStore {

        @Bean
        @Primary
        JobStore failingJobStore() {
            return JobStore.over(new JobRows() {
                @Override
                public <R> R inExclusiveScope(Function<Scope, R> work) {
                    throw new JobStoreException(SECRET);
                }
            });
        }
    }

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("every endpoint that touches the store answers 500 problem+json with a fixed detail")
    void everyStoreBackedEndpointAnswersWithAFixedProblem() throws Exception {
        String id = UUID.randomUUID().toString();
        String[][] calls = {
                {"POST", "/jobs", "{\"type\":\"send-email\"}"},
                {"GET", "/jobs", null},
                {"GET", "/jobs/" + id, null},
                {"POST", "/jobs/" + id + "/retry", null},
                {"DELETE", "/jobs/" + id, null},
                {"GET", "/stats", null}};

        for (String[] call : calls) {
            HttpResponse<String> response = send(call[0], call[1], call[2]);

            assertThat(response.statusCode()).as("%s %s", call[0], call[1]).isEqualTo(500);
            assertThat(response.headers().firstValue("content-type"))
                    .as("%s %s", call[0], call[1]).contains("application/problem+json");
            assertThat(response.body()).as("%s %s", call[0], call[1])
                    .isEqualTo("{\"type\":\"about:blank\",\"title\":\"Job store unavailable\","
                            + "\"status\":500,\"detail\":\"The job store could not complete the "
                            + "request\",\"instance\":\"" + call[1] + "\"}")
                    .doesNotContain("Hikari").doesNotContain("db.internal")
                    .doesNotContain("jdbc:");
        }
    }

    private HttpResponse<String> send(String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
