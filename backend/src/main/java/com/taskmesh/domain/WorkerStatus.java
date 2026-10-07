package com.taskmesh.domain;

/**
 * Worker lifecycle states (mirrors the PostgreSQL {@code worker_status} enum).
 */
public enum WorkerStatus {
    STARTING,
    IDLE,
    BUSY,
    DRAINING,
    OFFLINE,
    ERROR
}
