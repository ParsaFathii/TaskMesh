package com.taskmesh.repository;

import com.taskmesh.domain.Project;
import com.taskmesh.security.AuthenticatedUser;
import org.springframework.data.jpa.domain.Specification;

/**
 * Dynamic query fragments for project listing.
 */
public final class ProjectSpecifications {

    private ProjectSpecifications() {
    }

    /** USER sees own projects only; operators and admins see all. */
    public static Specification<Project> visibleTo(AuthenticatedUser actor) {
        if (actor.isOperatorOrAdmin()) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("ownerId"), actor.id());
    }
}
