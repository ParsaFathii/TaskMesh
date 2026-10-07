package com.taskmesh.domain;

/**
 * Lifecycle states of a job (mirrors the PostgreSQL {@code job_status} enum).
 * Legal transitions are defined in {@code JobStateMachine} (SPEC §5).
 */
public enum JobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    RETRYING,
    TIMED_OUT
}
