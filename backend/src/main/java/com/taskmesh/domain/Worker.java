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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A registered worker process. Workers write their own rows and heartbeats;
 * the backend only reads them and marks silent workers OFFLINE.
 */
@Entity
@Table(name = "workers")
public class Worker {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String hostname;

    @Column(nullable = false)
    private String version;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false)
    private WorkerStatus status = WorkerStatus.STARTING;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    private String[] capabilities = new String[0];

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "last_heartbeat", nullable = false)
    private OffsetDateTime lastHeartbeat;

    @Column(name = "heartbeat_interval_s", nullable = false)
    private int heartbeatIntervalS = 10;

    @Column(name = "current_job_id")
    private UUID currentJobId;

    @Column(name = "control_port")
    private Integer controlPort;

    protected Worker() {
    }

    public Worker(String name, String hostname, String version, String[] capabilities,
                  int heartbeatIntervalS, Integer controlPort) {
        this.name = name;
        this.hostname = hostname;
        this.version = version;
        this.capabilities = capabilities == null ? new String[0] : capabilities;
        this.heartbeatIntervalS = heartbeatIntervalS;
        this.controlPort = controlPort;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        this.startedAt = now;
        this.lastHeartbeat = now;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (startedAt == null) {
            startedAt = now;
        }
        if (lastHeartbeat == null) {
            lastHeartbeat = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHostname() {
        return hostname;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public WorkerStatus getStatus() {
        return status;
    }

    public void setStatus(WorkerStatus status) {
        this.status = status;
    }

    public String[] getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(String[] capabilities) {
        this.capabilities = capabilities == null ? new String[0] : capabilities;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(OffsetDateTime lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    public int getHeartbeatIntervalS() {
        return heartbeatIntervalS;
    }

    public void setHeartbeatIntervalS(int heartbeatIntervalS) {
        this.heartbeatIntervalS = heartbeatIntervalS;
    }

    public UUID getCurrentJobId() {
        return currentJobId;
    }

    public void setCurrentJobId(UUID currentJobId) {
        this.currentJobId = currentJobId;
    }

    public Integer getControlPort() {
        return controlPort;
    }

    public void setControlPort(Integer controlPort) {
        this.controlPort = controlPort;
    }
}
