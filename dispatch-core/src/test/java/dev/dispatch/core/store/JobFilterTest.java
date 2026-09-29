package dev.dispatch.core.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dispatch.core.job.JobState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Job filter")
class JobFilterTest {

    @Test
    @DisplayName("the limit is bounded to [1, MAX_LIMIT]")
    void limitIsBounded() {
        assertThatThrownBy(() -> new JobFilter(null, null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> new JobFilter(null, null, JobFilter.MAX_LIMIT + 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");

        assertThat(new JobFilter(null, null, 1, 0).limit()).isEqualTo(1);
        assertThat(new JobFilter(null, null, JobFilter.MAX_LIMIT, 0).limit())
                .isEqualTo(JobFilter.MAX_LIMIT);
    }

    @Test
    @DisplayName("the offset must not be negative")
    void offsetMustNotBeNegative() {
        assertThatThrownBy(() -> new JobFilter(null, null, 10, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset");
    }

    @Test
    @DisplayName("a type containing a NUL character is refused: no store can hold or match one")
    void typeMustNotContainNul() {
        assertThatThrownBy(() -> new JobFilter(null, "a\u0000b", 10, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("type must not contain NUL characters");
        assertThatThrownBy(() -> JobFilter.byType("\u0000"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(new JobFilter(null, "send-email", 10, 0).type()).isEqualTo("send-email");
        assertThat(new JobFilter(null, null, 10, 0).type()).isNull();
        assertThat(new JobFilter(null, "", 10, 0).type()).isEmpty();
    }

    @Test
    @DisplayName("withLimit and withOffset keep the rest of the filter")
    void withersKeepTheRest() {
        JobFilter filter = JobFilter.byState(JobState.PENDING).withLimit(5).withOffset(10);

        assertThat(filter.state()).isEqualTo(JobState.PENDING);
        assertThat(filter.type()).isNull();
        assertThat(filter.limit()).isEqualTo(5);
        assertThat(filter.offset()).isEqualTo(10);
    }
}
