package com.taskmesh.domain;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only audit trail of security-relevant actions. Metadata must never contain
 * passwords, tokens or other secrets.
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILURE = "FAILURE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_name")
    private String actorName;

    @Column(nullable = false)
    private String action;

    @Column(name = "resource_type")
    private String resourceType;

    @Column(name = "resource_id")
    private String resourceId;

    @Column(nullable = false)
    private String result;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected AuditLog() {
    }

    public AuditLog(UUID actorId, String actorName, String action, String resourceType, String resourceId,
                    String result, Map<String, Object> metadata) {
        this.actorId = actorId;
        this.actorName = actorName;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.result = result;
        this.metadata = metadata;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public Long getId() {
        return id;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getActorName() {
        return actorName;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getResult() {
        return result;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
