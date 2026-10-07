package com.taskmesh.repository;

import com.taskmesh.domain.AuditLog;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

/**
 * Dynamic query fragments for the audit log endpoint (GET /api/v1/logs).
 */
public final class AuditSpecifications {

    private AuditSpecifications() {
    }

    /**
     * Matches an actor by UUID or by exact name, whichever the caller's parameter
     * looks like.
     */
    public static Specification<AuditLog> actor(String actor) {
        final UUID asUuid;
        try {
            asUuid = UUID.fromString(actor);
        } catch (IllegalArgumentException e) {
            return (root, query, cb) -> cb.equal(root.get("actorName"), actor);
        }
        return (root, query, cb) -> cb.or(
                cb.equal(root.get("actorId"), asUuid),
                cb.equal(root.get("actorName"), actor));
    }

    public static Specification<AuditLog> action(String action) {
        return (root, query, cb) -> cb.equal(root.get("action"), action);
    }
}
