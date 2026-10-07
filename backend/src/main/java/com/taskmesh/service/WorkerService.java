package com.taskmesh.service;

import com.taskmesh.controller.ApiException;
import com.taskmesh.domain.Worker;
import com.taskmesh.repository.WorkerRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read-side access to registered workers. Workers register themselves by writing to the
 * {@code workers} table (SPEC §10); the backend only reads and enriches (staleness).
 */
@Service
public class WorkerService {

    private final WorkerRepository workerRepository;

    public WorkerService(WorkerRepository workerRepository) {
        this.workerRepository = workerRepository;
    }

    public Page<Worker> list(Pageable pageable) {
        return workerRepository.findAll(pageable);
    }

    public Worker get(UUID workerId) {
        return workerRepository.findById(workerId)
                .orElseThrow(() -> new ApiException.NotFound("Worker not found"));
    }

    /** A worker is stale when the last heartbeat is older than three heartbeat intervals. */
    public boolean isStale(Worker worker) {
        OffsetDateTime deadline = worker.getLastHeartbeat()
                .plus(Duration.ofSeconds(3L * worker.getHeartbeatIntervalS()));
        return OffsetDateTime.now().isAfter(deadline);
    }
}
