package com.taskmesh.controller.dto;

import com.taskmesh.domain.UserRole;

import java.util.UUID;

/**
 * Minimal user identity embedded in the login response and JWT.
 */
public record UserBrief(UUID id, String username, UserRole role) {
}
