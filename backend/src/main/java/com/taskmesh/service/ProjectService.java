package com.taskmesh.service;

import com.taskmesh.controller.ApiException;
import com.taskmesh.controller.dto.ProjectPatch;
import com.taskmesh.controller.dto.ProjectRequest;
import com.taskmesh.domain.Project;
import com.taskmesh.repository.ProjectRepository;
import com.taskmesh.repository.ProjectSpecifications;
import com.taskmesh.security.AuthenticatedUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Project CRUD with ownership isolation: USER sees and modifies only their own
 * projects, OPERATOR and ADMIN see all projects but only owners may modify them.
 */
@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final AuditService auditService;

    public ProjectService(ProjectRepository projectRepository, AuditService auditService) {
        this.projectRepository = projectRepository;
        this.auditService = auditService;
    }

    public Project create(AuthenticatedUser actor, ProjectRequest request) {
        Project project = projectRepository.save(
                new Project(request.name(), request.description(), actor.id()));
        auditService.success(actor.id(), actor.username(), "project.create", "project",
                project.getId().toString(), Map.of("name", project.getName()));
        return project;
    }

    /** 404 for invisible projects (USER vs. foreign project) — existence is not leaked. */
    public Project requireVisible(UUID projectId, AuthenticatedUser actor) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException.NotFound("Project not found"));
        if (!isVisible(project, actor)) {
            throw new ApiException.NotFound("Project not found");
        }
        return project;
    }

    public Project update(UUID projectId, AuthenticatedUser actor, ProjectPatch patch) {
        Project project = requireVisible(projectId, actor);
        if (!project.getOwnerId().equals(actor.id())) {
            throw new ApiException.Forbidden("Only the project owner may modify a project");
        }
        if (patch.name() != null) {
            project.setName(patch.name());
        }
        if (patch.description() != null) {
            project.setDescription(patch.description());
        }
        Project saved = projectRepository.save(project);
        auditService.success(actor.id(), actor.username(), "project.update", "project",
                project.getId().toString(), Map.of("name", saved.getName()));
        return saved;
    }

    public void delete(UUID projectId, AuthenticatedUser actor) {
        Project project = requireVisible(projectId, actor);
        if (!project.getOwnerId().equals(actor.id())) {
            throw new ApiException.Forbidden("Only the project owner may delete a project");
        }
        projectRepository.delete(project);
        auditService.success(actor.id(), actor.username(), "project.delete", "project",
                project.getId().toString(), Map.of("name", project.getName()));
    }

    /** USER sees own projects only; OPERATOR/ADMIN see everything. */
    public Page<Project> listFor(AuthenticatedUser actor, Pageable pageable) {
        return projectRepository.findAll(
                ProjectSpecifications.visibleTo(actor), pageable);
    }

    public boolean isVisible(Project project, AuthenticatedUser actor) {
        return actor.isOperatorOrAdmin() || project.getOwnerId().equals(actor.id());
    }
}
