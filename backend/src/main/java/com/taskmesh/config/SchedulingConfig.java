package com.taskmesh.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Enables the background maintenance sweeper. Tests disable it (taskmesh.scheduling-enabled=false)
 * so they can invoke the sweeper synchronously and deterministically.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "taskmesh", name = "scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
