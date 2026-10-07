package com.taskmesh.controller.dto;

import com.taskmesh.domain.Project;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Project representation.
 */
public record ProjectResponse(UUID id, String name, String description, UUID ownerId,
                              OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    public static ProjectResponse from(Project project) {
        return new ProjectResponse(project.getId(), project.getName(), project.getDescription(),
                project.getOwnerId(), project.getCreatedAt(), project.getUpdatedAt());
    }
}
