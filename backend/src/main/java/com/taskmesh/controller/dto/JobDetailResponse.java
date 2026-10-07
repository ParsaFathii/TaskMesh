package com.taskmesh.controller.dto;

import com.taskmesh.domain.Job;
import com.taskmesh.domain.Worker;

/**
 * Job detail response (GET /api/v1/jobs/{id}): the full job plus the worker summary.
 */
public record JobDetailResponse(JobResponse job, WorkerSummary worker) {

    public static JobDetailResponse from(Job job, Worker worker) {
        return new JobDetailResponse(JobResponse.from(job),
                worker == null ? null : WorkerSummary.from(worker));
    }
}
