package dev.dispatch.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.store.JobStore;
import dev.dispatch.core.store.memory.InMemoryJobStore;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The REST surface, exercised against the in-memory store.
 *
 * <p>Jobs are mostly submitted with a future {@code scheduledAt} so they sit still while the test
 * asserts on them — the queue is genuinely running in this context, and a job submitted for "now"
 * would be finished before the assertion ran.
 */
@SpringBootTest(properties = {
        "dispatch.store=memory",
        "dispatch.demo-handlers=false",
        "dispatch.concurrency=2",
        "dispatch.poll-interval=20ms",
        "dispatch.maintenance-interval=20ms",
        // Collapse the backoff so a job can burn its whole retry budget inside a test.
        "dispatch.retry.base-delay=1ms",
        "dispatch.retry.max-delay=1ms",
        "dispatch.retry.jitter-factor=0"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(TestHandlers.class)
@DisplayName("Job REST API")
class JobApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JobStore store;

    @Autowired
    private TestHandlers handlers;

    /**
     * One Spring context serves every method here, so the store carries over unless it is emptied.
     * Leaking jobs between tests turns every count assertion into a function of execution order.
     */
    @BeforeEach
    void resetStore() {
        ((InMemoryJobStore) store).clear();
    }

    @AfterEach
    void releaseBlockedHandlers() {
        handlers.release();
    }

    private String body(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    /** A job far enough in the future that no worker will touch it mid-test. */
    private Job scheduledJob(String type) {
        return store.insert(new JobSubmission(type, "{\"k\":\"v\"}", 0, 3,
                Instant.now().plus(Duration.ofHours(1))), Instant.now());
    }

    @Test
    @DisplayName("POST /jobs creates a job and returns its location")
    void submitJob() throws Exception {
        String request = """
                {
                  "type": "test-ok",
                  "payload": {"to": "someone@example.com", "subject": "Hello"},
                  "priority": 5,
                  "maxRetries": 2
                }""";

        mockMvc.perform(post("/jobs").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.type").value("test-ok"))
                .andExpect(jsonPath("$.priority").value(5))
                .andExpect(jsonPath("$.maxRetries").value(2))
                .andExpect(jsonPath("$.attempt").value(0))
                .andExpect(jsonPath("$.retriesRemaining").value(2))
                // The payload comes back as JSON, not as an escaped string.
                .andExpect(jsonPath("$.payload.to").value("someone@example.com"))
                .andExpect(jsonPath("$.state").value("PENDING"));
    }

    @Test
    @DisplayName("POST /jobs with a future scheduledAt creates a SCHEDULED job")
    void submitDelayedJob() throws Exception {
        Instant runAt = Instant.now().plus(Duration.ofHours(2));
        String request = body(new SubmitJobBody("test-ok", runAt));

        mockMvc.perform(post("/jobs").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("SCHEDULED"));
    }

    @Test
    @DisplayName("POST /jobs rejects a type no handler is registered for")
    void submitUnknownType() throws Exception {
        String request = """
                {"type": "no-such-handler", "payload": {}}""";

        mockMvc.perform(post("/jobs").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Unknown job type"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("no-such-handler")));
    }

    @Test
    @DisplayName("POST /jobs rejects a blank type and a negative retry budget")
    void submitInvalidRequest() throws Exception {
        mockMvc.perform(post("/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "  ", "payload": {}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.type").exists());

        mockMvc.perform(post("/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "test-ok", "maxRetries": -1}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.maxRetries").exists());
    }

    @Test
    @DisplayName("POST /jobs defaults priority, retries and schedule when they are omitted")
    void submitWithDefaults() throws Exception {
        mockMvc.perform(post("/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "test-ok"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value(0))
                .andExpect(jsonPath("$.maxRetries").value(3))
                .andExpect(jsonPath("$.payload").value(org.hamcrest.Matchers.anEmptyMap()));
    }

    @Test
    @DisplayName("GET /jobs/{id} returns the job, or 404")
    void getJob() throws Exception {
        Job job = scheduledJob("test-ok");

        mockMvc.perform(get("/jobs/{id}", job.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(job.id().toString()))
                .andExpect(jsonPath("$.state").value("SCHEDULED"));

        mockMvc.perform(get("/jobs/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Job not found"));
    }

    @Test
    @DisplayName("GET /jobs filters by status and by type")
    void listJobs() throws Exception {
        scheduledJob("test-ok");
        scheduledJob("test-ok");
        scheduledJob("test-fail");

        mockMvc.perform(get("/jobs").param("status", "SCHEDULED").param("type", "test-ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("test-ok"));

        mockMvc.perform(get("/jobs").param("type", "test-fail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/jobs").param("status", "COMPLETED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("GET /jobs rejects an unrecognised status and an out-of-range limit")
    void listRejectsBadParameters() throws Exception {
        mockMvc.perform(get("/jobs").param("status", "NOT_A_STATE"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/jobs").param("limit", "99999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("limit")));
    }

    @Test
    @DisplayName("GET /jobs pages through results")
    void listPages() throws Exception {
        for (int i = 0; i < 5; i++) {
            scheduledJob("test-ok");
        }

        mockMvc.perform(get("/jobs").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/jobs").param("limit", "2").param("offset", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("POST /jobs/{id}/retry revives a dead job with a fresh retry budget")
    void retryDeadJob() throws Exception {
        Job job = deadJob(2);
        // It got there the honest way: one attempt plus two retries.
        assertThat(job.attempt()).isEqualTo(3);
        assertThat(job.retriesRemaining()).isZero();

        mockMvc.perform(post("/jobs/{id}/retry", job.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.attempt").value(0))
                // The whole budget is handed back, not one last chance.
                .andExpect(jsonPath("$.retriesRemaining").value(2));
    }

    @Test
    @DisplayName("POST /jobs/{id}/retry is 409 for a job that is not dead, 404 for an unknown id")
    void retryRejectsLiveJob() throws Exception {
        Job job = scheduledJob("test-ok");

        mockMvc.perform(post("/jobs/{id}/retry", job.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("SCHEDULED")));

        mockMvc.perform(post("/jobs/{id}/retry", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /jobs/{id} cancels a job that has not started")
    void cancelJob() throws Exception {
        Job job = scheduledJob("test-ok");

        mockMvc.perform(delete("/jobs/{id}", job.id()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/jobs/{id}", job.id()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /jobs/{id} is 409 for a running job, 404 for an unknown id")
    void cancelRejectsRunningJob() throws Exception {
        // The blocking handler holds the job in RUNNING until the test releases it, so there is a
        // stable window to assert in. Letting the real pool claim it avoids racing the dispatcher
        // for the same row.
        Job job = store.insert(new JobSubmission("test-block", "{}", 0, 3, null), Instant.now());
        await().atMost(Duration.ofSeconds(10)).until(() ->
                store.find(job.id()).orElseThrow().state() == JobState.RUNNING);

        mockMvc.perform(delete("/jobs/{id}", job.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("RUNNING")));

        mockMvc.perform(delete("/jobs/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /stats reports queue depth per state and this instance's counters")
    void stats() throws Exception {
        // Bury one job first: deadJob() waits for the pool, and anything scheduled beforehand
        // would still be sitting there skewing the depth counts.
        Job dead = deadJob(0);
        scheduledJob("test-ok");
        scheduledJob("test-ok");

        mockMvc.perform(get("/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerId").exists())
                .andExpect(jsonPath("$.queueDepth.SCHEDULED").value(2))
                .andExpect(jsonPath("$.queueDepth.DEAD").value(1))
                .andExpect(jsonPath("$.queueDepth.PENDING").value(0))
                .andExpect(jsonPath("$.totalJobs").value(3))
                .andExpect(jsonPath("$.backlog").value(2))
                .andExpect(jsonPath("$.thisInstance.inFlight").value(0))
                .andExpect(jsonPath("$.thisInstance.failureRate").exists());

        assertThat(dead.state()).isEqualTo(JobState.DEAD);
    }

    /**
     * Produces a genuinely dead job by letting the queue bury it: the always-failing handler burns
     * through {@code maxRetries} and lands in the dead-letter state. Deterministic without
     * reaching around the worker pool and racing it for the row.
     */
    private Job deadJob(int maxRetries) {
        Job job = store.insert(
                new JobSubmission("test-fail", "{}", 0, maxRetries, null), Instant.now());
        await().atMost(Duration.ofSeconds(15)).until(() ->
                store.find(job.id()).orElseThrow().state() == JobState.DEAD);
        return store.find(job.id()).orElseThrow();
    }

    /** Minimal body for the delayed-submission case. */
    private record SubmitJobBody(String type, Instant scheduledAt) {
    }
}
