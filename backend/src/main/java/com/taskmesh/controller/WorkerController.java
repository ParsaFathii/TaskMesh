package com.taskmesh.controller;

import com.taskmesh.controller.dto.PageResponse;
import com.taskmesh.controller.dto.WorkerResponse;
import com.taskmesh.service.WorkerService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Worker registry endpoints (OPERATOR/ADMIN). Staleness is computed from heartbeats.
 */
@RestController
@RequestMapping("/api/v1/workers")
public class WorkerController {

    private final WorkerService workerService;

    public WorkerController(WorkerService workerService) {
        this.workerService = workerService;
    }

    @GetMapping
    public PageResponse<WorkerResponse> list(@RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        PageRequest pageable = (PageRequest) Pagination.toPage(page, size,
                Sort.by(Sort.Direction.DESC, "lastHeartbeat"));
        return PageResponse.of(workerService.list(pageable),
                worker -> WorkerResponse.from(worker, workerService.isStale(worker)));
    }

    @GetMapping("/{id}")
    public WorkerResponse get(@PathVariable UUID id) {
        var worker = workerService.get(id);
        return WorkerResponse.from(worker, workerService.isStale(worker));
    }
}
