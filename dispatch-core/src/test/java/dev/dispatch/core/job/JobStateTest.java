package dev.dispatch.core.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("JobState transition rules")
class JobStateTest {

    @Test
    @DisplayName("the full transition table is exactly what the lifecycle documents")
    void transitionTable() {
        assertThat(JobState.PENDING.allowedTransitions())
                .containsExactlyInAnyOrder(JobState.RUNNING, JobState.DEAD);
        assertThat(JobState.SCHEDULED.allowedTransitions())
                .containsExactlyInAnyOrder(JobState.PENDING, JobState.DEAD);
        assertThat(JobState.RUNNING.allowedTransitions())
                .containsExactlyInAnyOrder(JobState.COMPLETED, JobState.FAILED, JobState.DEAD,
                        JobState.PENDING);
        assertThat(JobState.FAILED.allowedTransitions())
                .containsExactlyInAnyOrder(JobState.PENDING, JobState.DEAD);
        assertThat(JobState.DEAD.allowedTransitions()).containsExactly(JobState.PENDING);
        assertThat(JobState.COMPLETED.allowedTransitions()).isEmpty();
    }

    @Test
    @DisplayName("COMPLETED is a one-way door")
    void completedIsFinal() {
        for (JobState next : JobState.values()) {
            assertThat(JobState.COMPLETED.canTransitionTo(next))
                    .as("COMPLETED -> %s", next)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("DEAD is escapable only by an operator requeue")
    void deadOnlyReturnsToPending() {
        assertThat(JobState.DEAD.canTransitionTo(JobState.PENDING)).isTrue();
        assertThat(JobState.DEAD.canTransitionTo(JobState.RUNNING)).isFalse();
        assertThat(JobState.DEAD.canTransitionTo(JobState.COMPLETED)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(JobState.class)
    @DisplayName("no state may transition to itself")
    void noSelfTransitions(JobState state) {
        assertThat(state.canTransitionTo(state)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(JobState.class)
    @DisplayName("every state declares a transition set, and the transition check agrees with it")
    void checkAgreesWithTable(JobState state) {
        Set<JobState> allowed = state.allowedTransitions();
        assertThat(allowed).isNotNull();
        for (JobState next : JobState.values()) {
            assertThat(state.canTransitionTo(next))
                    .as("%s -> %s", state, next)
                    .isEqualTo(allowed.contains(next));
        }
    }

    @Test
    @DisplayName("an illegal transition throws and says what was allowed")
    void illegalTransitionThrows() {
        assertThatThrownBy(() -> JobState.PENDING.requireTransitionTo(JobState.COMPLETED))
                .isInstanceOf(IllegalJobTransitionException.class)
                .hasMessageContaining("PENDING -> COMPLETED")
                .hasMessageContaining("RUNNING");

        IllegalJobTransitionException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalJobTransitionException.class,
                () -> JobState.COMPLETED.requireTransitionTo(JobState.PENDING));
        assertThat(thrown.from()).isEqualTo(JobState.COMPLETED);
        assertThat(thrown.to()).isEqualTo(JobState.PENDING);
    }

    @Test
    @DisplayName("a legal transition is silent")
    void legalTransitionDoesNotThrow() {
        JobState.PENDING.requireTransitionTo(JobState.RUNNING);
        JobState.RUNNING.requireTransitionTo(JobState.FAILED);
        JobState.FAILED.requireTransitionTo(JobState.PENDING);
    }

    @Test
    @DisplayName("terminal and pending-work classifications")
    void classification() {
        assertThat(EnumSet.allOf(JobState.class).stream().filter(JobState::isTerminal).toList())
                .containsExactlyInAnyOrder(JobState.COMPLETED, JobState.DEAD);
        assertThat(EnumSet.allOf(JobState.class).stream().filter(JobState::isPendingWork).toList())
                .containsExactlyInAnyOrder(JobState.PENDING, JobState.SCHEDULED, JobState.FAILED);
    }
}
