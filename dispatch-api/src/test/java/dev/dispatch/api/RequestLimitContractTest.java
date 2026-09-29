package dev.dispatch.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.store.JobStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * The request size limit over real HTTP: a body past {@code dispatch.max-payload-bytes} or nested
 * past 64 levels is refused as a 413 problem before it becomes a tree in memory, and a payload that
 * is already stored is still read back whole, whatever its size.
 *
 * <p>Jackson checks the document length as it loads each input buffer (about 8 KB), not per byte,
 * so the limit is exact only to within a buffer. The tests therefore use a limit well above one
 * buffer and pin only bodies clearly on either side of it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "dispatch.store=memory",
                "dispatch.max-payload-bytes=16384",
                "dispatch.poll-interval=1h",
                "dispatch.maintenance-interval=1h"
        })
@ActiveProfiles("dev")
@DisplayName("Request size limit")
class RequestLimitContractTest {

    private static final int LIMIT = 16384;
    private static final String FUTURE = "2027-01-01T00:00:00Z";
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private JobStore store;

    @BeforeEach
    void emptyStore() {
        store.deleteAll();
    }

    @Test
    @DisplayName("a body under the limit is accepted and one well over it is refused with a 413")
    void oversizeBodyIsRefused() throws Exception {
        HttpResponse<String> under = post(bodyOfLength(LIMIT / 2));
        assertThat(under.statusCode()).as(under.body()).isEqualTo(201);

        HttpResponse<String> over = post(bodyOfLength(LIMIT * 4));
        assertThat(over.statusCode()).isEqualTo(413);
        assertThat(over.headers().firstValue("content-type")).contains("application/problem+json");
        assertThat(over.body()).isEqualTo("{\"type\":\"about:blank\","
                + "\"title\":\"Request body too large\",\"status\":413,"
                + "\"detail\":\"The request body is larger than the 16384 bytes this server "
                + "accepts, or nests deeper than 64 levels\",\"instance\":\"/jobs\"}");
        assertThat(store.countsByState().values().stream().mapToLong(Long::longValue).sum())
                .as("only the accepted body was stored").isEqualTo(1);
    }

    @Test
    @DisplayName("a body far past the limit is refused, and the server carries on")
    void farPastTheLimit() throws Exception {
        assertThat(post(bodyOfLength(LIMIT * 40)).statusCode()).isEqualTo(413);

        assertThat(get("/stats").statusCode()).isEqualTo(200);
        assertThat(post(bodyOfLength(100)).statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("nesting is capped at 64 levels for the whole document, 63 for the payload")
    void nestingIsCapped() throws Exception {
        // The request object is level 1, so a payload of 63 nested arrays is 64 levels in all.
        assertThat(post(bodyWithNesting(63)).statusCode()).isEqualTo(201);

        HttpResponse<String> tooDeep = post(bodyWithNesting(64));
        assertThat(tooDeep.statusCode()).isEqualTo(413);
        assertThat(tooDeep.body()).contains("\"title\":\"Request body too large\"");
    }

    @Test
    @DisplayName("a payload stored before the limit existed is still returned as JSON")
    void storedPayloadsAreNotBound() throws Exception {
        String big = "{\"k\":\"" + "x".repeat(LIMIT * 10) + "\"}";
        Job job = store.insert(new JobSubmission("send-email", big, 0, 3,
                Instant.parse(FUTURE)), Instant.now());

        HttpResponse<String> response = get("/jobs/" + job.id());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"payload\":" + big + ",");
    }

    /** A valid request whose whole body is exactly {@code length} bytes. */
    private static String bodyOfLength(int length) {
        String head = "{\"type\":\"send-email\",\"scheduledAt\":\"" + FUTURE
                + "\",\"payload\":{\"k\":\"";
        String tail = "\"}}";
        int padding = length - head.length() - tail.length();
        assertThat(padding).as("body length %d is too short for the fixed parts", length)
                .isNotNegative();
        String body = head + "x".repeat(padding) + tail;
        assertThat(body.getBytes(StandardCharsets.UTF_8)).hasSize(length);
        return body;
    }

    private static String bodyWithNesting(int levels) {
        return "{\"type\":\"send-email\",\"scheduledAt\":\"" + FUTURE + "\",\"payload\":"
                + "[".repeat(levels) + "]".repeat(levels) + "}";
    }

    private HttpResponse<String> post(String json) throws IOException, InterruptedException {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/jobs"))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
