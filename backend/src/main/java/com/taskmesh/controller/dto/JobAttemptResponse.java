package com.taskmesh.controller.dto;

import com.taskmesh.domain.JobAttempt;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Attempt history entry (GET /api/v1/jobs/{id}/attempts).
 */
public record JobAttemptResponse(Long id, UUID jobId, int attemptNumber, UUID workerId,
                                 OffsetDateTime startedAt, OffsetDateTime finishedAt,
                                 String outcome, String error) {

    public static JobAttemptResponse from(JobAttempt attempt) {
        return new JobAttemptResponse(attempt.getId(), attempt.getJobId(), attempt.getAttemptNumber(),
                attempt.getWorkerId(), attempt.getStartedAt(), attempt.getFinishedAt(),
                attempt.getOutcome() == null ? null : attempt.getOutcome().name(), attempt.getError());
    }
}
