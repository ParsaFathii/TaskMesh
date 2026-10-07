package com.taskmesh.domain;

/**
 * Application roles (mirrors the PostgreSQL {@code user_role} enum), see SPEC §11.
 */
public enum UserRole {
    ADMIN,
    OPERATOR,
    USER
}
