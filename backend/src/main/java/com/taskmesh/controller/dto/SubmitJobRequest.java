package com.taskmesh.controller.dto;

import com.taskmesh.domain.JobPriority;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

/**
 * Job submission request (POST /api/v1/jobs). The payload is structurally validated
 * against the job type catalog; see GET /api/v1/job-types for the schemas.
 */
public record SubmitJobRequest(
        @NotNull UUID projectId,
        @NotBlank String type,
        JobPriority priority,
        @NotNull Map<String, Object> payload,
        @Min(0) @Max(10) Integer maxRetries,
        @Min(5) @Max(3600) Integer timeoutSeconds,
        @Size(max = 255) String idempotencyKey) {
}
