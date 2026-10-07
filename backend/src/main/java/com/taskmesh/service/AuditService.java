package com.taskmesh.service;

import com.taskmesh.domain.AuditLog;
import com.taskmesh.repository.AuditLogRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Writes the audit trail for security-relevant actions. Entries are inserted in the
 * caller's transaction when one is active. Metadata must never contain passwords or
 * tokens — services only pass identifiers and non-secret context.
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void success(UUID actorId, String actorName, String action, String resourceType,
                        String resourceId, Map<String, Object> metadata) {
        record(actorId, actorName, action, resourceType, resourceId, AuditLog.SUCCESS, metadata);
    }

    public void failure(UUID actorId, String actorName, String action, String resourceType,
                        String resourceId, Map<String, Object> metadata) {
        record(actorId, actorName, action, resourceType, resourceId, AuditLog.FAILURE, metadata);
    }

    private void record(UUID actorId, String actorName, String action, String resourceType,
                        String resourceId, String result, Map<String, Object> metadata) {
        repository.save(new AuditLog(actorId, actorName, action, resourceType, resourceId, result, metadata));
    }
}
