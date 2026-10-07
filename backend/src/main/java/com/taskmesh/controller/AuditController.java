package com.taskmesh.controller;

import com.taskmesh.controller.dto.AuditLogResponse;
import com.taskmesh.controller.dto.PageResponse;
import com.taskmesh.repository.AuditLogRepository;
import com.taskmesh.repository.AuditSpecifications;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audit log inspection (OPERATOR/ADMIN).
 */
@RestController
@RequestMapping("/api/v1/logs")
public class AuditController {

    private final AuditLogRepository repository;

    public AuditController(AuditLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public PageResponse<AuditLogResponse> list(@RequestParam(required = false) String actor,
                                               @RequestParam(required = false) String action,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        PageRequest pageable = (PageRequest) Pagination.toPage(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Specification<com.taskmesh.domain.AuditLog> spec = null;
        if (actor != null && !actor.isBlank()) {
            spec = AuditSpecifications.actor(actor);
        }
        if (action != null && !action.isBlank()) {
            Specification<com.taskmesh.domain.AuditLog> actionSpec = AuditSpecifications.action(action);
            spec = spec == null ? actionSpec : spec.and(actionSpec);
        }
        return PageResponse.of(repository.findAll(spec, pageable), AuditLogResponse::from);
    }
}
