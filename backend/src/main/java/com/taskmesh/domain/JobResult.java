package com.taskmesh.domain;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.UUID;

/**
 * The result of a finished job: either inline JSON (small results) or a file
 * reference relative to the storage root (large results).
 */
@Entity
@Table(name = "job_results")
public class JobResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false, unique = true)
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false)
    private ResultKind kind;

    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode inline;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "checksum_sha256")
    private String checksumSha256;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected JobResult() {
    }

    public JobResult(UUID jobId, ResultKind kind) {
        this.jobId = jobId;
        this.kind = kind;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public UUID getId() {
        return id;
    }

    public UUID getJobId() {
        return jobId;
    }

    public ResultKind getKind() {
        return kind;
    }

    public JsonNode getInline() {
        return inline;
    }

    public void setInline(JsonNode inline) {
        this.inline = inline;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public void setChecksumSha256(String checksumSha256) {
        this.checksumSha256 = checksumSha256;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
