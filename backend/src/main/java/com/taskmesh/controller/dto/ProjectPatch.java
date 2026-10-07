package com.taskmesh.controller.dto;

import jakarta.validation.constraints.Size;

/**
 * Partial project update: only provided fields are changed.
 */
public record ProjectPatch(
        @Size(min = 1, max = 200) String name,
        @Size(max = 2000) String description) {
}
