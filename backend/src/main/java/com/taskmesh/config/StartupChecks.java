package com.taskmesh.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Startup checks that should be visible to operators in the logs.
 */
@Component
public class StartupChecks {

    private static final Logger log = LoggerFactory.getLogger(StartupChecks.class);

    private final TaskMeshProperties properties;

    public StartupChecks(TaskMeshProperties properties) {
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (properties.usingDefaultJwtSecret()) {
            log.warn("TASKMESH_JWT_SECRET is not set - using the built-in development secret. "
                    + "Set TASKMESH_JWT_SECRET to a strong value in production.");
        }
    }
}
