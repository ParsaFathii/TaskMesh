package com.taskmesh.security;

import com.taskmesh.domain.UserRole;

import java.util.UUID;

/**
 * Principal extracted from a verified JWT (also used to authenticate WebSocket connections).
 *
 * @param id       user id (JWT subject)
 * @param username login name
 * @param role     application role
 */
public record AuthenticatedUser(UUID id, String username, UserRole role) {

    public boolean isOperatorOrAdmin() {
        return role == UserRole.OPERATOR || role == UserRole.ADMIN;
    }
}
