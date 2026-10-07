package com.taskmesh.domain;

/**
 * Claim-ordering priority (mirrors the PostgreSQL {@code job_priority} enum).
 */
public enum JobPriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL
}
