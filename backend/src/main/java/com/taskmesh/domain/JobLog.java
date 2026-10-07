package com.taskmesh.domain;

import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Structured log line produced by a worker during job execution.
 */
@Entity
@Table(name = "job_logs")
public class JobLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "worker_id")
    private UUID workerId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false)
    private LogLevel level;

    @Column(nullable = false)
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected JobLog() {
    }

    public JobLog(UUID jobId, UUID workerId, LogLevel level, String message, Map<String, Object> metadata) {
        this.jobId = jobId;
        this.workerId = workerId;
        this.level = level;
        this.message = message;
        this.metadata = metadata;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public Long getId() {
        return id;
    }

    public UUID getJobId() {
        return jobId;
    }

    public UUID getWorkerId() {
        return workerId;
    }

    public LogLevel getLevel() {
        return level;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
