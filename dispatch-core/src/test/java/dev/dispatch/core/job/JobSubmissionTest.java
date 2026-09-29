package dev.dispatch.core.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("JobSubmission validation")
class JobSubmissionTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static JobSubmission scheduledAt(Instant scheduledAt) {
        return new JobSubmission("send-email", "{}", 0, 3, scheduledAt);
    }

    @Test
    @DisplayName("no scheduledAt means as soon as possible")
    void nullScheduleIsAccepted() {
        assertThat(scheduledAt(null).scheduledAt()).isNull();
    }

    @ParameterizedTest(name = "{0} is accepted")
    @ValueSource(strings = {
            "0001-01-01T00:00:00Z",
            "1970-01-01T00:00:00Z",
            "2026-12-24T09:00:00Z",
            "9999-12-31T23:59:59Z",
            "9999-12-31T23:59:59.500Z",
            "9999-12-31T23:59:59.999999999Z"})
    void instantsInsideTheFourDigitYearRangeAreAccepted(String instant) {
        assertThatCode(() -> scheduledAt(Instant.parse(instant))).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} is rejected")
    @ValueSource(strings = {
            "0000-12-31T23:59:59.999999999Z",
            "-4713-01-01T00:00:00Z",
            "-100000-01-01T00:00:00Z",
            "+10000-01-01T00:00:00Z",
            "+294277-01-01T00:00:00Z",
            "+300000-01-01T00:00:00Z",
            "-1000000000-01-01T00:00:00Z",
            "+1000000000-12-31T23:59:59.999999999Z"})
    void instantsOutsideTheRangeAreRejectedWithTheRangeInTheMessage(String instant) {
        assertThatThrownBy(() -> scheduledAt(Instant.parse(instant)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheduledAt must be between "
                        + JobSubmission.EARLIEST_SCHEDULED_AT + " and "
                        + JobSubmission.LATEST_SCHEDULED_AT)
                .hasMessageContaining(instant);
    }

    @Test
    @DisplayName("the bounds are the first and last instants of years 0001 and 9999")
    void boundsAreTheEdgesOfTheFourDigitYears() {
        assertThat(JobSubmission.EARLIEST_SCHEDULED_AT).isEqualTo("0001-01-01T00:00:00Z");
        assertThat(JobSubmission.LATEST_SCHEDULED_AT).isEqualTo("9999-12-31T23:59:59.999999999Z");
    }

    @Test
    @DisplayName("a delay that lands past year 9999 is rejected the same way")
    void hugeDelayIsRejected() {
        assertThatThrownBy(() -> JobSubmission.delayed(
                "send-email", "{}", Duration.ofDays(365L * 9000), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheduledAt must be between");
    }

    @Test
    @DisplayName("the existing checks on type and retry budget are unchanged")
    void otherChecksStillApply() {
        assertThatThrownBy(() -> new JobSubmission("  ", "{}", 0, 3, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("type must not be blank");
        assertThatThrownBy(() -> new JobSubmission("t", "{}", 0, -1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxRetries must not be negative: -1");
    }
}
