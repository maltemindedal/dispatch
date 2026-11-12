package dev.dispatch.core.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("JobState transition rules")
class JobStateTest {

    @Test
    @DisplayName("the full transition table is exactly what the lifecycle documents")
    void transitionTable() {
        assertThat(JobState.PENDING.allowedTransitions()).containsExactly(JobState.RUNNING);
        assertThat(JobState.SCHEDULED.allowedTransitions()).containsExactly(JobState.PENDING);
        assertThat(JobState.RUNNING.allowedTransitions())
                .containsExactlyInAnyOrder(JobState.COMPLETED, JobState.FAILED, JobState.DEAD,
                        JobState.PENDING);
        assertThat(JobState.FAILED.allowedTransitions()).containsExactly(JobState.PENDING);
        assertThat(JobState.DEAD.allowedTransitions()).containsExactly(JobState.PENDING);
        assertThat(JobState.COMPLETED.allowedTransitions()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(JobState.class)
    @DisplayName("only a RUNNING job can be dead-lettered; an unstarted one is cancelled instead")
    void onlyRunningDeadLetters(JobState state) {
        assertThat(state.allowedTransitions().contains(JobState.DEAD))
                .as("%s -> DEAD", state)
                .isEqualTo(state == JobState.RUNNING);
    }

    @Test
    @DisplayName("COMPLETED is a one-way door")
    void completedIsFinal() {
        for (JobState next : JobState.values()) {
            assertThat(JobState.COMPLETED.allowedTransitions().contains(next))
                    .as("COMPLETED -> %s", next)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("DEAD is escapable only by an operator's manual retry")
    void deadOnlyReturnsToPending() {
        assertThat(JobState.DEAD.allowedTransitions().contains(JobState.PENDING)).isTrue();
        assertThat(JobState.DEAD.allowedTransitions().contains(JobState.RUNNING)).isFalse();
        assertThat(JobState.DEAD.allowedTransitions().contains(JobState.COMPLETED)).isFalse();
    }

    @Test
    @DisplayName("each transition moves to one state, from states other than that one")
    void transitionsAreWellFormed() {
        for (Transition transition : Transition.values()) {
            assertThat(transition.from()).as("%s from", transition).isNotEmpty()
                    .doesNotContain(transition.to());
        }
    }

    @Test
    @DisplayName("an illegal transition throws and says what was allowed")
    void illegalTransitionThrows() {
        assertThatThrownBy(() -> Transition.COMPLETE.applyTo(JobState.PENDING))
                .isInstanceOf(IllegalJobTransitionException.class)
                .hasMessageContaining("PENDING -> COMPLETED")
                .hasMessageContaining("RUNNING");

        IllegalJobTransitionException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalJobTransitionException.class,
                () -> Transition.MANUAL_RETRY.applyTo(JobState.COMPLETED));
        assertThat(thrown.from()).isEqualTo(JobState.COMPLETED);
        assertThat(thrown.to()).isEqualTo(JobState.PENDING);
        assertThat(thrown.transition()).isEqualTo(Transition.MANUAL_RETRY);
    }

    @Test
    @DisplayName("a legal transition is silent")
    void legalTransitionDoesNotThrow() {
        assertThat(Transition.CLAIM.applyTo(JobState.PENDING)).isEqualTo(JobState.RUNNING);
        assertThat(Transition.FAIL.applyTo(JobState.RUNNING)).isEqualTo(JobState.FAILED);
        assertThat(Transition.PROMOTE.applyTo(JobState.FAILED)).isEqualTo(JobState.PENDING);
    }

    @Test
    @DisplayName("a move into a state is refused from a source state that move does not name")
    void transitionChecksTheMoveNotThePair() {
        assertThatThrownBy(() -> Transition.PROMOTE.applyTo(JobState.DEAD))
                .isInstanceOf(IllegalJobTransitionException.class)
                .hasMessageContaining("PROMOTE from DEAD");
        assertThatThrownBy(() -> Transition.LEASE_EXPIRED.applyTo(JobState.FAILED))
                .isInstanceOf(IllegalJobTransitionException.class);
        assertThatThrownBy(() -> Transition.MANUAL_RETRY.applyTo(JobState.RUNNING))
                .isInstanceOf(IllegalJobTransitionException.class);
    }

    @Test
    @DisplayName("only jobs that have not started are cancellable")
    void cancellableClassification() {
        assertThat(EnumSet.allOf(JobState.class).stream().filter(JobState::isCancellable).toList())
                .containsExactlyInAnyOrder(JobState.PENDING, JobState.SCHEDULED);
    }

    @Test
    @DisplayName("zeroCounts covers every state and is mutable")
    void zeroCountsCoversEveryState() {
        var counts = JobState.zeroCounts();
        assertThat(counts).hasSize(JobState.values().length);
        assertThat(counts.values()).allMatch(count -> count == 0L);
        counts.merge(JobState.PENDING, 1L, Long::sum);
        assertThat(counts.get(JobState.PENDING)).isEqualTo(1L);
    }
}
