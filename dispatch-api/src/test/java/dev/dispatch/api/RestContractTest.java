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
 * Pins the wire contract byte for byte: status codes, content types, and the exact JSON of every
 * success and error body, over real HTTP.
 *
 * <p>{@link JobApiTest} checks individual fields through MockMvc, which cannot see key order,
 * number formatting, escaping, null omission, or how the servlet container frames a response. Those
 * are exactly what a Jackson, Spring or Tomcat upgrade can change without failing a field assertion,
 * so this suite exists to fail when they do. It talks to the server with the JDK's own HTTP client
 * so it does not move with Spring's test-client APIs.
 *
 * <p>Ids and instants that the server generates are replaced by placeholders before comparing;
 * everything else is compared as written. Errors the framework renders before any of our handlers
 * run (unknown path, unsupported method or media type) are Boot's default error object, which
 * carries no message.
 *
 * <p>The queue is inert here (an hour-long poll and sweep interval), so jobs stay in the state the
 * test puts them in. Jobs in states the API cannot create are placed by driving the store directly.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "dispatch.store=memory",
                "dispatch.poll-interval=1h",
                "dispatch.maintenance-interval=1h"
        })
@ActiveProfiles("dev")
@DisplayName("REST wire contract")
class RestContractTest {

    private static final String JSON = "application/json";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String FUTURE = "2027-01-01T00:00:00Z";
    private static final Instant PAST = Instant.parse("2026-01-01T00:00:00Z");
    private static final String WORKER = "contract-worker";
    private static final String UNKNOWN_ID = "00000000-0000-0000-0000-000000000000";

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private JobStore store;

    @BeforeEach
    void emptyStore() {
        store.deleteAll();
    }

    // ------------------------------------------------------------------ submit: success shapes

    @Test
    @DisplayName("POST /jobs stores nested JSON verbatim: numbers, unicode, null, order")
    void submitNestedPayload() {
        Response r = post("""
                {"type":"send-email","payload":{"to":"a@b.c","n":1,"f":1.0,"e":1e3,"d":0.1000,\
                "big":12345678901234567890,"neg":-0.0,"nul":null,"arr":[1,"two",{"z":3,"a":4}],\
                "uni":"héllo ☃ 😀","nested":{"b":1,"a":2}},\
                "priority":5,"maxRetries":7,"scheduledAt":"%s"}""".formatted(FUTURE));

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.contentType()).isEqualTo(JSON);
        assertThat(r.location()).matches("/jobs/[0-9a-f-]{36}");
        // The emoji comes back as the character itself. Jackson 2 wrote a character outside the
        // Basic Multilingual Plane as an escaped surrogate pair; Jackson 3 writes it as UTF-8.
        // Both spell the same JSON string, so the only thing that changed is how it is spelled.
        assertThat(r.normalized()).isEqualTo("{\"id\":\"<id>\",\"type\":\"send-email\","
                + "\"payload\":{\"to\":\"a@b.c\",\"n\":1,\"f\":1.0,\"e\":1000.0,\"d\":0.1,"
                + "\"big\":12345678901234567890,\"neg\":-0.0,\"nul\":null,"
                + "\"arr\":[1,\"two\",{\"z\":3,\"a\":4}],"
                + "\"uni\":\"héllo ☃ 😀\",\"nested\":{\"b\":1,\"a\":2}},"
                + "\"priority\":5,\"maxRetries\":7,\"attempt\":0,\"retriesRemaining\":7,"
                + "\"state\":\"SCHEDULED\",\"scheduledAt\":\"2027-01-01T00:00:00Z\","
                + "\"createdAt\":\"<ts>\",\"updatedAt\":\"<ts>\"}");
        assertThat(r.location()).isEqualTo("/jobs/" + r.field("id"));
    }

    @Test
    @DisplayName("instants are ISO-8601 strings with the fraction the store holds, never numbers")
    void instantsAreIsoStrings() {
        Response r = post("{\"type\":\"send-email\",\"scheduledAt\":\"" + FUTURE + "\"}");

        // normalized() masks these two fields, so read them from the raw body.
        assertThat(r.body()).containsPattern(
                "\"createdAt\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z\"");
        assertThat(r.body()).containsPattern(
                "\"updatedAt\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z\"");
        assertThat(r.body()).contains("\"scheduledAt\":\"2027-01-01T00:00:00Z\"");
    }

    @Test
    @DisplayName("POST /jobs with only a type takes the documented defaults and an empty payload")
    void submitMinimal() {
        Response r = post("{\"type\":\"resize-image\",\"scheduledAt\":\"" + FUTURE + "\"}");

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.normalized()).isEqualTo("{\"id\":\"<id>\",\"type\":\"resize-image\","
                + "\"payload\":{},\"priority\":0,\"maxRetries\":3,\"attempt\":0,"
                + "\"retriesRemaining\":3,\"state\":\"SCHEDULED\","
                + "\"scheduledAt\":\"2027-01-01T00:00:00Z\","
                + "\"createdAt\":\"<ts>\",\"updatedAt\":\"<ts>\"}");
    }

    @Test
    @DisplayName("POST /jobs accepts a string, array, number or null payload")
    void submitNonObjectPayloads() {
        assertThat(post("{\"type\":\"send-email\",\"payload\":\"just text\","
                + "\"scheduledAt\":\"" + FUTURE + "\"}").normalized())
                .contains("\"payload\":\"just text\",\"priority\":0");
        assertThat(post("{\"type\":\"send-email\",\"payload\":[1,2,3],"
                + "\"scheduledAt\":\"" + FUTURE + "\"}").normalized())
                .contains("\"payload\":[1,2,3],\"priority\":0");
        assertThat(post("{\"type\":\"send-email\",\"payload\":1.10,"
                + "\"scheduledAt\":\"" + FUTURE + "\"}").normalized())
                .contains("\"payload\":1.1,\"priority\":0");
        assertThat(post("{\"type\":\"send-email\",\"payload\":null,"
                + "\"scheduledAt\":\"" + FUTURE + "\"}").normalized())
                .contains("\"payload\":{},\"priority\":0");
    }

    @Test
    @DisplayName("POST /jobs reports scheduledAt as UTC with only the fraction it has")
    void submitNormalizesScheduledAt() {
        assertThat(post("{\"type\":\"send-email\",\"scheduledAt\":\"2027-01-01T00:00:00.123456Z\"}")
                .normalized()).contains("\"scheduledAt\":\"2027-01-01T00:00:00.123456Z\"");
        assertThat(post("{\"type\":\"send-email\",\"scheduledAt\":\"2027-01-01T01:02:03+02:00\"}")
                .normalized()).contains("\"scheduledAt\":\"2026-12-31T23:02:03Z\"");
    }

    @Test
    @DisplayName("POST /jobs keeps a negative priority and ignores unknown fields")
    void submitNegativePriorityAndUnknownField() {
        assertThat(post("{\"type\":\"send-email\",\"priority\":-3,\"scheduledAt\":\""
                + FUTURE + "\"}").normalized()).contains("\"priority\":-3,\"maxRetries\":3");

        Response r = post("{\"type\":\"send-email\",\"bogus\":1,\"scheduledAt\":\"" + FUTURE
                + "\"}");
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.normalized()).doesNotContain("bogus");
    }

    // ------------------------------------------------------------------ submit: errors

    @Test
    @DisplayName("POST /jobs validation errors are problem+json with a per-field errors map")
    void submitValidationErrors() {
        Response both = post("{\"type\":\"  \",\"maxRetries\":-1}");
        assertThat(both.status()).isEqualTo(400);
        assertThat(both.contentType()).isEqualTo(PROBLEM_JSON);
        String head = "{\"type\":\"about:blank\",\"title\":\"Invalid request\",\"status\":400,"
                + "\"detail\":\"The request body failed validation\",\"instance\":\"/jobs\","
                + "\"errors\":{";
        String typeError = "\"type\":\"type is required\"";
        String retriesError = "\"maxRetries\":\"maxRetries must not be negative\"";
        // The order of the entries follows the validator's set iteration, so it is not pinned.
        assertThat(both.normalized()).isIn(
                head + typeError + "," + retriesError + "}}",
                head + retriesError + "," + typeError + "}}");

        assertThat(post("{\"payload\":{}}").normalized()).isEqualTo("{\"type\":\"about:blank\","
                + "\"title\":\"Invalid request\",\"status\":400,"
                + "\"detail\":\"The request body failed validation\",\"instance\":\"/jobs\","
                + "\"errors\":{\"type\":\"type is required\"}}");

        assertThat(post("{\"type\":\"" + "x".repeat(300) + "\"}").normalized())
                .isEqualTo("{\"type\":\"about:blank\",\"title\":\"Invalid request\","
                        + "\"status\":400,\"detail\":\"The request body failed validation\","
                        + "\"instance\":\"/jobs\","
                        + "\"errors\":{\"type\":\"type must be at most 255 characters\"}}");
    }

    @Test
    @DisplayName("POST /jobs for a type nobody handles is 422 problem+json")
    void submitUnknownType() {
        Response r = post("{\"type\":\"nope\",\"scheduledAt\":\"" + FUTURE + "\"}");

        assertThat(r.status()).isEqualTo(422);
        assertThat(r.contentType()).isEqualTo(PROBLEM_JSON);
        assertThat(r.normalized()).isEqualTo("{\"type\":\"about:blank\","
                + "\"title\":\"Unknown job type\",\"status\":422,"
                + "\"detail\":\"No handler registered for job type 'nope'; "
                + "known types: [resize-image, send-email]\",\"instance\":\"/jobs\"}");
    }

    @Test
    @DisplayName("POST /jobs with a body that is not readable JSON is a 400 problem with a fixed detail")
    void submitUnreadableBodies() {
        // Whatever is wrong with the body, the answer is the same and quotes none of it: Jackson's
        // own message names Java classes and echoes the offending text.
        String detail = "The request body is missing or is not valid JSON of the expected shape";

        assertProblem(send("POST", "/jobs", JSON, "{not json"), 400, "Invalid request", detail,
                "/jobs");
        assertProblem(send("POST", "/jobs", JSON, ""), 400, "Invalid request", detail, "/jobs");
        assertProblem(post("{\"type\":\"send-email\",\"scheduledAt\":\"tomorrow\"}"), 400,
                "Invalid request", detail, "/jobs");
        assertProblem(post("{\"type\":\"send-email\",\"maxRetries\":\"many\"}"), 400,
                "Invalid request", detail, "/jobs");
    }

    @Test
    @DisplayName("POST /jobs with an unsupported media type is Boot's default 415 error JSON")
    void submitUnsupportedMediaTypes() {
        Response plain = send("POST", "/jobs", "text/plain", "type=x");
        assertThat(plain.status()).isEqualTo(415);
        assertThat(plain.contentType()).isEqualTo(JSON);
        assertFrameworkError(plain, 415, "Unsupported Media Type", "/jobs");

        Response none = send("POST", "/jobs", null, "{}");
        assertThat(none.status()).isEqualTo(415);
        assertFrameworkError(none, 415, "Unsupported Media Type", "/jobs");
    }

    // ------------------------------------------------------------------ read

    @Test
    @DisplayName("GET /jobs/{id} returns the stored job; unknown id is 404, bad id is 400")
    void getJob() {
        String id = post("{\"type\":\"send-email\",\"payload\":{\"k\":\"v\"},\"scheduledAt\":\""
                + FUTURE + "\"}").field("id");

        Response found = get("/jobs/" + id);
        assertThat(found.status()).isEqualTo(200);
        assertThat(found.contentType()).isEqualTo(JSON);
        assertThat(found.normalized()).isEqualTo("{\"id\":\"<id>\",\"type\":\"send-email\","
                + "\"payload\":{\"k\":\"v\"},\"priority\":0,\"maxRetries\":3,\"attempt\":0,"
                + "\"retriesRemaining\":3,\"state\":\"SCHEDULED\","
                + "\"scheduledAt\":\"2027-01-01T00:00:00Z\","
                + "\"createdAt\":\"<ts>\",\"updatedAt\":\"<ts>\"}");

        Response missing = get("/jobs/" + UNKNOWN_ID);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.contentType()).isEqualTo(PROBLEM_JSON);
        assertThat(missing.normalized()).isEqualTo("{\"type\":\"about:blank\","
                + "\"title\":\"Job not found\",\"status\":404,"
                + "\"detail\":\"No job with id <id>\",\"instance\":\"/jobs/<id>\"}");

        Response badId = get("/jobs/not-a-uuid");
        assertThat(badId.status()).isEqualTo(400);
        assertThat(badId.contentType()).isEqualTo(PROBLEM_JSON);
        assertThat(badId.normalized()).isEqualTo("{\"type\":\"about:blank\","
                + "\"title\":\"Invalid request\",\"status\":400,"
                + "\"detail\":\"Invalid UUID string: not-a-uuid\","
                + "\"instance\":\"/jobs/not-a-uuid\"}");
    }

    @Test
    @DisplayName("GET /jobs lists newest first and honours status, type, limit and offset")
    void listJobs() {
        post("{\"type\":\"send-email\",\"priority\":1,\"scheduledAt\":\"" + FUTURE + "\"}");
        post("{\"type\":\"resize-image\",\"priority\":2,\"scheduledAt\":\"" + FUTURE + "\"}");
        post("{\"type\":\"send-email\",\"priority\":3,\"scheduledAt\":\"" + FUTURE + "\"}");

        Response all = get("/jobs");
        assertThat(all.status()).isEqualTo(200);
        assertThat(all.contentType()).isEqualTo(JSON);
        assertThat(all.normalized()).isEqualTo("["
                + scheduled("send-email", 3) + ","
                + scheduled("resize-image", 2) + ","
                + scheduled("send-email", 1) + "]");

        assertThat(get("/jobs?status=SCHEDULED&type=resize-image").normalized())
                .isEqualTo("[" + scheduled("resize-image", 2) + "]");
        assertThat(get("/jobs?limit=2&offset=1").normalized())
                .isEqualTo("[" + scheduled("resize-image", 2) + ","
                        + scheduled("send-email", 1) + "]");
        assertThat(get("/jobs?status=DEAD").normalized()).isEqualTo("[]");
        assertThat(get("/jobs?type=unheard-of").normalized()).isEqualTo("[]");
    }

    @Test
    @DisplayName("GET /jobs rejects bad parameters with problem+json")
    void listRejectsBadParameters() {
        assertProblem(get("/jobs?status=BOGUS"), 400, "Invalid request",
                "No enum constant dev.dispatch.core.job.JobState.BOGUS", "/jobs");
        assertProblem(get("/jobs?status=scheduled"), 400, "Invalid request",
                "No enum constant dev.dispatch.core.job.JobState.scheduled", "/jobs");
        assertProblem(get("/jobs?limit=0"), 400, "Invalid request",
                "limit must be within [1, 1000]: 0", "/jobs");
        assertProblem(get("/jobs?limit=1001"), 400, "Invalid request",
                "limit must be within [1, 1000]: 1001", "/jobs");
        assertProblem(get("/jobs?limit=abc"), 400, "Invalid request",
                "For input string: \\\"abc\\\"", "/jobs");
        assertProblem(get("/jobs?offset=-1"), 400, "Invalid request",
                "offset must not be negative: -1", "/jobs");
        assertProblem(get("/jobs?type=%00"), 400, "Invalid request",
                "type must not contain NUL characters", "/jobs");
        assertProblem(get("/jobs?type=abc%00def"), 400, "Invalid request",
                "type must not contain NUL characters", "/jobs");
    }

    @Test
    @DisplayName("GET /jobs for a media type it cannot produce is an empty 406")
    void listNotAcceptable() {
        Response r = send("GET", "/jobs", null, null, "Accept", "application/xml");

        assertThat(r.status()).isEqualTo(406);
        assertThat(r.body()).isEmpty();
    }

    // ------------------------------------------------------------------ actions

    @Test
    @DisplayName("POST /jobs/{id}/retry: 409 for a live job, 404 for an unknown one")
    void retryRefusals() {
        String id = post("{\"type\":\"send-email\",\"scheduledAt\":\"" + FUTURE + "\"}")
                .field("id");

        Response live = send("POST", "/jobs/" + id + "/retry", null, null);
        assertThat(live.status()).isEqualTo(409);
        assertThat(live.contentType()).isEqualTo(PROBLEM_JSON);
        assertThat(live.normalized()).isEqualTo("{\"type\":\"about:blank\","
                + "\"title\":\"Job is in the wrong state\",\"status\":409,"
                + "\"detail\":\"Job <id> is SCHEDULED and can only be retried from [DEAD]\","
                + "\"instance\":\"/jobs/<id>/retry\"}");

        assertProblem(send("POST", "/jobs/" + UNKNOWN_ID + "/retry", null, null), 404,
                "Job not found", "No job with id " + UNKNOWN_ID,
                "/jobs/" + UNKNOWN_ID + "/retry");
    }

    @Test
    @DisplayName("POST /jobs/{id}/retry revives a dead job with a fresh budget")
    void retryDeadJob() {
        Job dead = deadJob(2);

        Response r = send("POST", "/jobs/" + dead.id() + "/retry", null, null);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.contentType()).isEqualTo(JSON);
        // The revived job is due immediately, so its scheduledAt is "now"; its last error stays.
        assertThat(r.normalized().replaceAll("\"scheduledAt\":\"[^\"]*\"", "\"scheduledAt\":\"<ts>\""))
                .isEqualTo("{\"id\":\"<id>\",\"type\":\"send-email\","
                        + "\"payload\":{},\"priority\":0,\"maxRetries\":2,\"attempt\":0,"
                        + "\"retriesRemaining\":2,\"state\":\"PENDING\",\"scheduledAt\":\"<ts>\","
                        + "\"createdAt\":\"<ts>\",\"updatedAt\":\"<ts>\",\"lastError\":\"boom\"}");
    }

    @Test
    @DisplayName("GET /jobs/{id} shows the lease and last error of a claimed and of a dead job")
    void leaseAndErrorFieldsAreExposed() {
        Job dead = deadJob(0);
        Job running = insertDue("resize-image");
        store.claim(WORKER, 1, Duration.ofMinutes(5), Instant.now());

        assertThat(get("/jobs/" + dead.id()).normalized()).isEqualTo("{\"id\":\"<id>\","
                + "\"type\":\"send-email\",\"payload\":{},\"priority\":0,\"maxRetries\":0,"
                + "\"attempt\":1,\"retriesRemaining\":0,\"state\":\"DEAD\","
                + "\"scheduledAt\":\"2026-01-01T00:00:00Z\",\"createdAt\":\"<ts>\","
                + "\"updatedAt\":\"<ts>\",\"lastError\":\"boom\"}");
        assertThat(get("/jobs/" + running.id()).normalized()).isEqualTo("{\"id\":\"<id>\","
                + "\"type\":\"resize-image\",\"payload\":{},\"priority\":0,\"maxRetries\":3,"
                + "\"attempt\":1,\"retriesRemaining\":3,\"state\":\"RUNNING\","
                + "\"scheduledAt\":\"2026-01-01T00:00:00Z\",\"createdAt\":\"<ts>\","
                + "\"updatedAt\":\"<ts>\",\"lockedUntil\":\"<ts>\","
                + "\"lockedBy\":\"" + WORKER + "\"}");
    }

    @Test
    @DisplayName("DELETE /jobs/{id}: 204 then 404; 409 for a running job")
    void cancel() {
        String id = post("{\"type\":\"send-email\",\"scheduledAt\":\"" + FUTURE + "\"}")
                .field("id");

        Response deleted = send("DELETE", "/jobs/" + id, null, null);
        assertThat(deleted.status()).isEqualTo(204);
        assertThat(deleted.body()).isEmpty();

        assertProblem(send("DELETE", "/jobs/" + id, null, null), 404, "Job not found",
                "No job with id " + id, "/jobs/" + id);

        Job running = insertDue("send-email");
        store.claim(WORKER, 1, Duration.ofMinutes(5), Instant.now());
        Response conflict = send("DELETE", "/jobs/" + running.id(), null, null);
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.contentType()).isEqualTo(PROBLEM_JSON);
        assertThat(conflict.normalized()).isEqualTo("{\"type\":\"about:blank\","
                + "\"title\":\"Job is in the wrong state\",\"status\":409,"
                + "\"detail\":\"Job <id> is RUNNING and can only be cancelled from "
                + "[PENDING, SCHEDULED]\",\"instance\":\"/jobs/<id>\"}");
    }

    // ------------------------------------------------------------------ routing

    @Test
    @DisplayName("Unknown paths, methods and a trailing slash use Boot's default error JSON")
    void routingErrors() {
        Response unknown = get("/nope");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.contentType()).isEqualTo(JSON);
        assertFrameworkError(unknown, 404, "Not Found", "/nope");

        Response put = send("PUT", "/jobs", JSON, "{\"type\":\"x\"}");
        assertThat(put.status()).isEqualTo(405);
        // Which order the methods are listed in varies from run to run; the set is the contract.
        assertThat(put.header("allow")).contains("GET").contains("POST").doesNotContain("PUT");
        assertFrameworkError(put, 405, "Method Not Allowed", "/jobs");

        Response slash = get("/jobs/");
        assertThat(slash.status()).isEqualTo(404);
        assertFrameworkError(slash, 404, "Not Found", "/jobs/");

        assertThat(get("/actuator/health").status()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ helpers

    /** A SCHEDULED job as it appears in a listing, for the jobs {@link #listJobs} submits. */
    private static String scheduled(String type, int priority) {
        return "{\"id\":\"<id>\",\"type\":\"" + type + "\",\"payload\":{},\"priority\":"
                + priority + ",\"maxRetries\":3,\"attempt\":0,\"retriesRemaining\":3,"
                + "\"state\":\"SCHEDULED\",\"scheduledAt\":\"2027-01-01T00:00:00Z\","
                + "\"createdAt\":\"<ts>\",\"updatedAt\":\"<ts>\"}";
    }

    /** A PENDING job, due since {@link #PAST}. Nothing claims it: the queue is inert. */
    private Job insertDue(String type) {
        return store.insert(new JobSubmission(type, "{}", 0, 3, PAST), Instant.now());
    }

    /**
     * Buries a job by hand: claim it, then dead-letter it. Only possible because no dispatcher
     * is competing for the row.
     */
    private Job deadJob(int maxRetries) {
        Job job = store.insert(new JobSubmission("send-email", "{}", 0, maxRetries, PAST),
                Instant.now());
        store.claim(WORKER, 1, Duration.ofMinutes(5), Instant.now());
        return store.deadLetter(job.id(), WORKER, 1, "boom", Instant.now()).orElseThrow();
    }

    private void assertProblem(
            Response r, int status, String title, String detail, String instance) {
        assertThat(r.status()).isEqualTo(status);
        assertThat(r.contentType()).isEqualTo(PROBLEM_JSON);
        assertThat(r.normalized()).isEqualTo(mask("{\"type\":\"about:blank\",\"title\":\"" + title
                + "\",\"status\":" + status + ",\"detail\":\"" + detail + "\",\"instance\":\""
                + instance + "\"}"));
    }

    /**
     * Boot's default error body for what the framework rejects before a controller runs:
     * {@code timestamp, status, error, path}, in that order. There is deliberately no
     * {@code message}: its wording is the framework's, and for some errors it names Java classes.
     */
    private void assertFrameworkError(Response r, int status, String error, String path) {
        assertThat(r.normalized()).isEqualTo("{\"timestamp\":\"<ts>\",\"status\":" + status
                + ",\"error\":\"" + error + "\",\"path\":\"" + path + "\"}");
    }

    private Response post(String json) {
        return send("POST", "/jobs", JSON, json);
    }

    private Response get(String path) {
        return send("GET", path, null, null);
    }

    private Response send(String method, String path, String contentType, String body,
            String... extraHeaders) {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        for (int i = 0; i < extraHeaders.length; i += 2) {
            request.header(extraHeaders[i], extraHeaders[i + 1]);
        }
        try {
            HttpResponse<String> response = CLIENT.send(
                    request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response);
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(method + " " + path + " interrupted", e);
        }
    }

    /** Replaces every UUID with a placeholder, in what came back and in what is expected. */
    private static String mask(String text) {
        return text.replaceAll(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<id>");
    }

    /** What came back, with the server-generated parts maskable. */
    private record Response(HttpResponse<String> raw) {

        int status() {
            return raw.statusCode();
        }

        String body() {
            return raw.body();
        }

        String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }

        String location() {
            return header("location");
        }

        /** The media type without parameters; Spring appends none to these responses. */
        String contentType() {
            return header("content-type");
        }

        /** A top-level string field of a JSON object body, read without a JSON library. */
        String field(String name) {
            String needle = "\"" + name + "\":\"";
            int start = body().indexOf(needle) + needle.length();
            return body().substring(start, body().indexOf('"', start));
        }

        /** The body with generated ids, instants and the worker id replaced by placeholders. */
        String normalized() {
            return mask(body())
                    .replaceAll("\"(createdAt|updatedAt|lockedUntil|timestamp)\":\"[^\"]*\"",
                            "\"$1\":\"<ts>\"")
                    .replaceAll("\"workerId\":\"[^\"]*\"", "\"workerId\":\"<worker>\"");
        }

        @Override
        public String toString() {
            return status() + " " + raw.headers().map() + " " + body();
        }
    }
}
