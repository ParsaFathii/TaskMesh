package com.taskmesh.domain;

/**
 * Log severity levels for job logs (mirrors the PostgreSQL {@code log_level} enum).
 */
public enum LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}
