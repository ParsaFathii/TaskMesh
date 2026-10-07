package com.taskmesh.controller.dto;

import com.taskmesh.domain.JobAttempt;
import com.taskmesh.domain.JobLog;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Job log line as served by GET /api/v1/jobs/{id}/logs (newest first).
 */
public record JobLogResponse(Long id, UUID jobId, UUID workerId, String level, String message,
                             Map<String, Object> metadata, OffsetDateTime createdAt) {

    public static JobLogResponse from(JobLog log) {
        return new JobLogResponse(log.getId(), log.getJobId(), log.getWorkerId(),
                log.getLevel().name(), log.getMessage(), log.getMetadata(), log.getCreatedAt());
    }
}
