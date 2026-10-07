package com.taskmesh.controller;

import com.taskmesh.controller.dto.PageResponse;
import com.taskmesh.controller.dto.ProjectPatch;
import com.taskmesh.controller.dto.ProjectRequest;
import com.taskmesh.controller.dto.ProjectResponse;
import com.taskmesh.security.AuthenticatedUser;
import com.taskmesh.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * Project CRUD. Visibility and ownership are enforced in the service layer
 * (USER: own projects only; OPERATOR/ADMIN: read all, modify nothing they do not own).
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public PageResponse<ProjectResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        PageRequest pageable = (PageRequest) Pagination.toPage(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(projectService.listFor(user, pageable), ProjectResponse::from);
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @Valid @RequestBody ProjectRequest request) {
        ProjectResponse project = ProjectResponse.from(projectService.create(user, request));
        URI location = UriComponentsBuilder.fromPath("/api/v1/projects/{id}")
                .buildAndExpand(project.id()).toUri();
        return ResponseEntity.created(location).body(project);
    }

    @GetMapping("/{id}")
    public ProjectResponse get(@AuthenticationPrincipal AuthenticatedUser user,
                               @PathVariable UUID id) {
        return ProjectResponse.from(projectService.requireVisible(id, user));
    }

    @PatchMapping("/{id}")
    public ProjectResponse update(@AuthenticationPrincipal AuthenticatedUser user,
                                  @PathVariable UUID id,
                                  @Valid @RequestBody ProjectPatch patch) {
        return ProjectResponse.from(projectService.update(id, user, patch));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user,
                                       @PathVariable UUID id) {
        projectService.delete(id, user);
        return ResponseEntity.noContent().build();
    }
}
