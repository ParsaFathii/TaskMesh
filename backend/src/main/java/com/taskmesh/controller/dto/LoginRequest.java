package com.taskmesh.controller.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Login request body.
 *
 * @param username account name
 * @param password plaintext password (never stored or logged)
 */
public record LoginRequest(
        @NotBlank(message = "username is required") String username,
        @NotBlank(message = "password is required") String password) {
}
