package com.taskmesh.domain;

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
 * One execution attempt of a job, written by the claiming worker and finalized by
 * the worker (normal outcomes) or by the backend sweeper (ABANDONED / TIMED_OUT / CANCELLED).
 */
@Entity
@Table(name = "job_attempts")
public class JobAttempt {

    /** Outcome values recorded in the free-text {@code outcome} column. */
    public enum Outcome {
        SUCCEEDED,
        FAILED,
        TIMED_OUT,
        CANCELLED,
        ABANDONED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "worker_id")
    private UUID workerId;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Enumerated(EnumType.STRING)
    private Outcome outcome;

    private String error;

    protected JobAttempt() {
    }

    public JobAttempt(UUID jobId, int attemptNumber, UUID workerId, OffsetDateTime startedAt) {
        this.jobId = jobId;
        this.attemptNumber = attemptNumber;
        this.workerId = workerId;
        this.startedAt = startedAt == null ? OffsetDateTime.now(ZoneOffset.UTC) : startedAt;
    }

    public void finish(Outcome outcome, String error) {
        this.outcome = outcome;
        this.error = error;
        this.finishedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public Long getId() {
        return id;
    }

    public UUID getJobId() {
        return jobId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public UUID getWorkerId() {
        return workerId;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getError() {
        return error;
    }
}
