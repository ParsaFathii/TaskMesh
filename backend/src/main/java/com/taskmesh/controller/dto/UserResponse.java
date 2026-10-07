package com.taskmesh.controller.dto;

import com.taskmesh.domain.User;
import com.taskmesh.domain.UserRole;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * User representation without secrets.
 */
public record UserResponse(UUID id, String username, String email, UserRole role,
                           OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getRole(),
                user.getCreatedAt(), user.getUpdatedAt());
    }
}
