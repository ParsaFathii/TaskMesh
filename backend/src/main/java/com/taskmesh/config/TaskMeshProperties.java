package com.taskmesh.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TaskMesh-specific configuration mapped from the {@code taskmesh.*} property namespace.
 * Values are sourced from environment variables via application.yml placeholder resolution.
 *
 * @param jwtSecret                  HS256 signing secret (TASKMESH_JWT_SECRET)
 * @param storageDir                 root directory for file job results (TASKMESH_STORAGE_DIR)
 * @param corsOrigins                comma-separated allowed origins (TASKMESH_CORS_ORIGINS)
 * @param loginRateLimitPerMinute    login attempts allowed per username per rolling minute
 * @param schedulingEnabled          enables the @Scheduled maintenance sweeper (disabled in tests)
 * @param listenerEnabled            enables the PostgreSQL LISTEN/NOTIFY listener thread
 * @param eventChannel               PostgreSQL NOTIFY channel shared with the workers
 */
@ConfigurationProperties(prefix = "taskmesh")
public record TaskMeshProperties(
        String jwtSecret,
        String storageDir,
        String corsOrigins,
        int loginRateLimitPerMinute,
        boolean schedulingEnabled,
        boolean listenerEnabled,
        String eventChannel
) {

    public static final String DEFAULT_JWT_SECRET = "taskmesh-dev-secret-change-me";

    public boolean usingDefaultJwtSecret() {
        return jwtSecret == null || DEFAULT_JWT_SECRET.equals(jwtSecret.trim());
    }

    public String[] corsOriginArray() {
        if (corsOrigins == null || corsOrigins.isBlank()) {
            return new String[0];
        }
        return java.util.Arrays.stream(corsOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }
}
