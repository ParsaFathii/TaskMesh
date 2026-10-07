package com.taskmesh.controller.dto;

import com.taskmesh.domain.Job;
import com.taskmesh.domain.Worker;
import com.taskmesh.domain.WorkerStatus;

import java.util.UUID;

/**
 * Compact worker block embedded in the job detail response.
 */
public record WorkerSummary(UUID id, String name, String hostname, WorkerStatus status) {

    public static WorkerSummary from(Worker worker) {
        return new WorkerSummary(worker.getId(), worker.getName(), worker.getHostname(),
                worker.getStatus());
    }
}
