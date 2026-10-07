package com.taskmesh.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Project creation request.
 */
public record ProjectRequest(
        @NotBlank @Size(min = 1, max = 200) String name,
        @Size(max = 2000) String description) {
}
