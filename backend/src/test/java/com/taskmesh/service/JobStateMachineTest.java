package com.taskmesh.service;

import com.taskmesh.controller.ApiException;
import com.taskmesh.domain.JobStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exhaustive verification of the job state machine (SPEC §5): every legal transition
 * is accepted, every other combination rejected with 409 CONFLICT.
 */
class JobStateMachineTest {

    private final JobStateMachine machine = new JobStateMachine();

    @Test
    void legalTransitionsAreAccepted() {
        assertThat(machine.canTransition(JobStatus.QUEUED, JobStatus.RUNNING)).isTrue();
        assertThat(machine.canTransition(JobStatus.QUEUED, JobStatus.CANCELLED)).isTrue();

        assertThat(machine.canTransition(JobStatus.RUNNING, JobStatus.SUCCEEDED)).isTrue();
        assertThat(machine.canTransition(JobStatus.RUNNING, JobStatus.FAILED)).isTrue();
        assertThat(machine.canTransition(JobStatus.RUNNING, JobStatus.RETRYING)).isTrue();
        assertThat(machine.canTransition(JobStatus.RUNNING, JobStatus.TIMED_OUT)).isTrue();
        assertThat(machine.canTransition(JobStatus.RUNNING, JobStatus.CANCELLED)).isTrue();

        assertThat(machine.canTransition(JobStatus.RETRYING, JobStatus.RUNNING)).isTrue();
        assertThat(machine.canTransition(JobStatus.RETRYING, JobStatus.CANCELLED)).isTrue();

        assertThat(machine.canTransition(JobStatus.FAILED, JobStatus.QUEUED)).isTrue();
        assertThat(machine.canTransition(JobStatus.TIMED_OUT, JobStatus.QUEUED)).isTrue();
        assertThat(machine.canTransition(JobStatus.CANCELLED, JobStatus.QUEUED)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(JobStatus.class)
    void allIllegalTransitionsAreRejected(JobStatus from) {
        EnumSet<JobStatus> legal = legalTargets(from);
        for (JobStatus to : JobStatus.values()) {
            if (to == from) {
                continue;
            }
            if (legal.contains(to)) {
                assertThatCode(() -> machine.assertTransition(from, to))
                        .as("%s -> %s should be legal", from, to)
                        .doesNotThrowAnyException();
            } else {
                assertThatThrownBy(() -> machine.assertTransition(from, to))
                        .as("%s -> %s should be illegal", from, to)
                        .isInstanceOf(ApiException.Conflict.class);
            }
        }
    }

    @Test
    void selfTransitionsAreRejected() {
        for (JobStatus status : JobStatus.values()) {
            assertThat(machine.canTransition(status, status)).isFalse();
        }
    }

    @Test
    void succeededJobsAreTerminal() {
        assertThat(machine.canTransition(JobStatus.SUCCEEDED, JobStatus.QUEUED)).isFalse();
        assertThat(machine.canTransition(JobStatus.SUCCEEDED, JobStatus.RUNNING)).isFalse();
        assertThat(machine.canTransition(JobStatus.SUCCEEDED, JobStatus.CANCELLED)).isFalse();
    }

    @Test
    void backoffIsExponentialAndCapped() {
        assertThat(JobStateMachine.backoffSeconds(1)).isEqualTo(10);
        assertThat(JobStateMachine.backoffSeconds(2)).isEqualTo(20);
        assertThat(JobStateMachine.backoffSeconds(3)).isEqualTo(40);
        assertThat(JobStateMachine.backoffSeconds(4)).isEqualTo(80);
        assertThat(JobStateMachine.backoffSeconds(5)).isEqualTo(160);
        assertThat(JobStateMachine.backoffSeconds(6)).isEqualTo(300);
        assertThat(JobStateMachine.backoffSeconds(20)).isEqualTo(300);
    }

    private EnumSet<JobStatus> legalTargets(JobStatus from) {
        return switch (from) {
            case QUEUED -> EnumSet.of(JobStatus.RUNNING, JobStatus.CANCELLED);
            case RUNNING -> EnumSet.of(JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.RETRYING,
                    JobStatus.TIMED_OUT, JobStatus.CANCELLED);
            case RETRYING -> EnumSet.of(JobStatus.RUNNING, JobStatus.CANCELLED);
            case FAILED, TIMED_OUT, CANCELLED -> EnumSet.of(JobStatus.QUEUED);
            case SUCCEEDED -> EnumSet.noneOf(JobStatus.class);
        };
    }
}
