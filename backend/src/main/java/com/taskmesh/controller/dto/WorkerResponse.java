package com.taskmesh.controller.dto;

import com.taskmesh.domain.Worker;
import com.taskmesh.domain.WorkerStatus;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;

/**
 * Worker representation including the computed staleness flag.
 */
public record WorkerResponse(UUID id, String name, String hostname, String version,
                             WorkerStatus status, String[] capabilities, OffsetDateTime startedAt,
                             OffsetDateTime lastHeartbeat, int heartbeatIntervalS,
                             UUID currentJobId, Integer controlPort, boolean stale) {

    public static WorkerResponse from(Worker worker, boolean stale) {
        return new WorkerResponse(worker.getId(), worker.getName(), worker.getHostname(),
                worker.getVersion(), worker.getStatus(),
                worker.getCapabilities() == null ? new String[0] : worker.getCapabilities(),
                worker.getStartedAt(), worker.getLastHeartbeat(), worker.getHeartbeatIntervalS(),
                worker.getCurrentJobId(), worker.getControlPort(), stale);
    }
}
