package com.taskmesh.service;

import com.taskmesh.controller.ApiException;
import com.taskmesh.domain.JobStatus;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Central job state machine (SPEC §5). Every status change — whether triggered by a
 * user (cancel, retry), by the workers, or by the maintenance sweeper — must pass
 * through this component.
 */
@Component
public class JobStateMachine {

    private static final Map<JobStatus, Set<JobStatus>> ALLOWED = Map.of(
            JobStatus.QUEUED, EnumSet.of(JobStatus.RUNNING, JobStatus.CANCELLED),
            JobStatus.RUNNING, EnumSet.of(JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.RETRYING,
                    JobStatus.TIMED_OUT, JobStatus.CANCELLED),
            JobStatus.RETRYING, EnumSet.of(JobStatus.RUNNING, JobStatus.CANCELLED),
            JobStatus.FAILED, EnumSet.of(JobStatus.QUEUED),
            JobStatus.TIMED_OUT, EnumSet.of(JobStatus.QUEUED),
            JobStatus.CANCELLED, EnumSet.of(JobStatus.QUEUED),
            JobStatus.SUCCEEDED, EnumSet.noneOf(JobStatus.class));

    public boolean canTransition(JobStatus from, JobStatus to) {
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(JobStatus.class)).contains(to);
    }

    /**
     * Validates a transition, throwing a 409 CONFLICT error for illegal moves.
     */
    public void assertTransition(JobStatus from, JobStatus to) {
        if (!canTransition(from, to)) {
            throw new ApiException.Conflict("Illegal job state transition " + from + " -> " + to);
        }
    }

    /**
     * Backoff applied when a job enters RETRYING, in seconds:
     * {@code min(300, 2^retryCount * 5)} with retryCount counted <em>after</em> the increment.
     */
    public static int backoffSeconds(int nextRetryCount) {
        long exponential = (1L << Math.min(nextRetryCount, 20)) * 5;
        return (int) Math.min(300, exponential);
    }
}
