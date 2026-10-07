package com.taskmesh.controller.dto;

import com.taskmesh.domain.Job;
import com.taskmesh.domain.JobPriority;
import com.taskmesh.domain.JobStatus;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Job representation used in listings.
 */
public record JobResponse(UUID id, UUID projectId, UUID ownerId, String type, JobPriority priority,
                          Map<String, Object> payload, JobStatus status, String idempotencyKey,
                          OffsetDateTime createdAt, OffsetDateTime queuedAt, OffsetDateTime startedAt,
                          OffsetDateTime completedAt, int retryCount, int maxRetries,
                          int timeoutSeconds, UUID workerId, Integer progress,
                          boolean cancelRequested, String lastError) {

    public static JobResponse from(Job job) {
        return new JobResponse(job.getId(), job.getProjectId(), job.getOwnerId(), job.getType(),
                job.getPriority(), job.getPayload(), job.getStatus(), job.getIdempotencyKey(),
                job.getCreatedAt(), job.getQueuedAt(), job.getStartedAt(), job.getCompletedAt(),
                job.getRetryCount(), job.getMaxRetries(), job.getTimeoutSeconds(), job.getWorkerId(),
                job.getProgress(), job.isCancelRequested(), job.getLastError());
    }
}
