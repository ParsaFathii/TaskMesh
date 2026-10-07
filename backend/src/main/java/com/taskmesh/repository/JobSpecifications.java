package com.taskmesh.repository;

import com.taskmesh.domain.Job;
import com.taskmesh.domain.JobPriority;
import com.taskmesh.domain.JobStatus;
import com.taskmesh.security.AuthenticatedUser;
import org.springframework.data.jpa.domain.Specification;

/**
 * Dynamic query fragments for job listing (filters of GET /api/v1/jobs).
 */
public final class JobSpecifications {

    private JobSpecifications() {
    }

    public static Specification<Job> visibleTo(AuthenticatedUser actor) {
        if (actor.isOperatorOrAdmin()) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("ownerId"), actor.id());
    }

    public static Specification<Job> projectId(java.util.UUID projectId) {
        return (root, query, cb) -> cb.equal(root.get("projectId"), projectId);
    }

    public static Specification<Job> status(JobStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Job> type(String type) {
        return (root, query, cb) -> cb.equal(root.get("type"), type);
    }

    public static Specification<Job> priority(JobPriority priority) {
        return (root, query, cb) -> cb.equal(root.get("priority"), priority);
    }
}
