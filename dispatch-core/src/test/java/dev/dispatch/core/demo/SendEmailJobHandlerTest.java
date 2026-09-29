package dev.dispatch.core.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dispatch.core.handler.JobContext;
import dev.dispatch.core.handler.PermanentJobFailureException;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobSubmission;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The bundled send-email simulator: its permanent-failure rule and what it says about it. */
@DisplayName("Demo send-email handler")
class SendEmailJobHandlerTest {

    private static JobContext contextFor(String payload) {
        Job job = Job.newJob(UUID.randomUUID(), JobSubmission.of(SendEmailJobHandler.TYPE, payload),
                Instant.parse("2026-01-01T00:00:00Z"));
        return new JobContext(job, "test-worker");
    }

    /** A handler that never fails on its own and does not sleep. */
    private static SendEmailJobHandler reliableAndInstant() {
        return new SendEmailJobHandler(0.0, Duration.ZERO, Duration.ZERO);
    }

    @Test
    @DisplayName("a payload without a recipient is a permanent failure")
    void missingRecipientIsPermanent() {
        assertThatThrownBy(() -> reliableAndInstant().handle(contextFor("{\"subject\":\"Hi\"}")))
                .isInstanceOf(PermanentJobFailureException.class)
                .hasMessageContaining("Payload has no \"to\" field");
        assertThatThrownBy(() -> reliableAndInstant().handle(contextFor(null)))
                .isInstanceOf(PermanentJobFailureException.class);
    }

    @Test
    @DisplayName("the failure does not repeat the payload: it is stored, logged and returned by the API")
    void failureMessageDoesNotEchoThePayload() {
        String payload = "{\"subject\":\"a-private-subject\",\"body\":\"" + "x".repeat(5_000) + "\"}";

        assertThatThrownBy(() -> reliableAndInstant().handle(contextFor(payload)))
                .isInstanceOf(PermanentJobFailureException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain("a-private-subject")
                        .hasSizeLessThan(200));
    }

    @Test
    @DisplayName("a payload with a recipient is sent and counted")
    void sendsWhenThereIsARecipient() throws Exception {
        SendEmailJobHandler handler = reliableAndInstant();

        assertThatCode(() -> handler.handle(contextFor("{\"to\":\"someone@example.com\"}")))
                .doesNotThrowAnyException();

        assertThat(handler.sentCount()).isEqualTo(1);
        assertThat(handler.attemptCount()).isEqualTo(1);
    }
}
