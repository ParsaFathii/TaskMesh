package com.taskmesh.controller.dto;

import com.taskmesh.domain.AuditLog;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Audit trail entry (GET /api/v1/logs).
 */
public record AuditLogResponse(Long id, UUID actorId, String actorName, String action,
                               String resourceType, String resourceId, String result,
                               Map<String, Object> metadata, OffsetDateTime createdAt) {

    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(log.getId(), log.getActorId(), log.getActorName(), log.getAction(),
                log.getResourceType(), log.getResourceId(), log.getResult(), log.getMetadata(),
                log.getCreatedAt());
    }
}
