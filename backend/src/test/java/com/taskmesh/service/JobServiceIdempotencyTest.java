package com.taskmesh.service;

import com.taskmesh.catalog.JobTypeCatalog;
import com.taskmesh.controller.ApiException;
import com.taskmesh.controller.dto.SubmitJobRequest;
import com.taskmesh.domain.Job;
import com.taskmesh.domain.JobPriority;
import com.taskmesh.domain.Project;
import com.taskmesh.domain.UserRole;
import com.taskmesh.messaging.EventNotifier;
import com.taskmesh.repository.JobRepository;
import com.taskmesh.repository.ProjectRepository;
import com.taskmesh.repository.WorkerRepository;
import com.taskmesh.repository.JobAttemptRepository;
import com.taskmesh.repository.JobLogRepository;
import com.taskmesh.repository.JobResultRepository;
import com.taskmesh.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level idempotency semantics of job submission: a known key replays the stored
 * job, unknown keys create a new one, and invalid submissions never reach the repository.
 */
class JobServiceIdempotencyTest {

    private final JobRepository jobRepository = Mockito.mock(JobRepository.class);
    private final ProjectRepository projectRepository = Mockito.mock(ProjectRepository.class);
    private final AuditService auditService = Mockito.mock(AuditService.class);
    private final EventNotifier notifier = Mockito.mock(EventNotifier.class);

    private final JobService service = new JobService(
            jobRepository, projectRepository, Mockito.mock(WorkerRepository.class),
            Mockito.mock(JobLogRepository.class), Mockito.mock(JobAttemptRepository.class),
            Mockito.mock(JobResultRepository.class), new JobTypeCatalog(),
            new JobStateMachine(), auditService, notifier);

    private final UUID ownerId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final AuthenticatedUser actor = new AuthenticatedUser(ownerId, "ada", UserRole.USER);

    @Test
    void duplicateKeyReplaysStoredJobWithoutCreatingANewOne() {
        Job stored = job();
        when(jobRepository.findByOwnerIdAndIdempotencyKey(ownerId, "same-key"))
                .thenReturn(Optional.of(stored));

        JobService.Submission result = service.submit(actor, request("same-key"), null);

        assertThat(result.duplicate()).isTrue();
        assertThat(result.job()).isSameAs(stored);
        verify(jobRepository, never()).saveAndFlush(any());
        verify(auditService, never()).success(any(), any(), any(), any(), any(), any());
    }

    @Test
    void headerKeyTakesEffectWhenBodyKeyIsAbsent() {
        Job stored = job();
        when(jobRepository.findByOwnerIdAndIdempotencyKey(ownerId, "header-key"))
                .thenReturn(Optional.of(stored));

        JobService.Submission result = service.submit(actor, request(null), "header-key");

        assertThat(result.duplicate()).isTrue();
        assertThat(result.job()).isSameAs(stored);
    }

    @Test
    void newSubmissionIsCreatedAndNotified() {
        when(projectRepository.findById(projectId))
                .thenReturn(Optional.of(new Project("p", "", ownerId)));
        when(jobRepository.saveAndFlush(any(Job.class)))
                .thenAnswer(invocation -> {
                    Job job = invocation.getArgument(0);
                    org.springframework.test.util.ReflectionTestUtils
                            .setField(job, "id", UUID.randomUUID());
                    return job;
                });

        JobService.Submission result = service.submit(actor, request("fresh-key"), null);

        assertThat(result.duplicate()).isFalse();
        assertThat(result.job().getId()).isNotNull();
        verify(jobRepository).saveAndFlush(any(Job.class));
        verify(notifier).queueEvent(result.job().getId(), "job.enqueued");
        verify(auditService).success(Mockito.eq(ownerId), Mockito.eq("ada"), Mockito.eq("job.create"),
                Mockito.eq("job"), Mockito.eq(result.job().getId().toString()), any());
    }

    @Test
    void foreignProjectIsNotFoundForPlainUser() {
        when(projectRepository.findById(projectId))
                .thenReturn(Optional.of(new Project("p", "", UUID.randomUUID())));
        SubmitJobRequest body = new SubmitJobRequest(projectId, "text_statistics",
                JobPriority.NORMAL, Map.of("text", "hello taskmesh"), null, null, null);

        assertThatThrownBy(() -> service.submit(actor, body, null))
                .isInstanceOf(ApiException.NotFound.class);
        verify(jobRepository, never()).saveAndFlush(any());
    }

    @Test
    void invalidPayloadIsRejectedBeforeAnyPersistence() {
        when(projectRepository.findById(projectId))
                .thenReturn(Optional.of(new Project("p", "", ownerId)));

        SubmitJobRequest badPayload = new SubmitJobRequest(projectId, "text_statistics",
                JobPriority.NORMAL, Map.of("caseSensitive", true), null, null, null);

        assertThatThrownBy(() -> service.submit(actor, badPayload, null))
                .isInstanceOf(ApiException.Validation.class)
                .hasMessageContaining("required field is missing");
        verify(jobRepository, never()).saveAndFlush(any());
    }

    private SubmitJobRequest request(String idempotencyKey) {
        when(projectRepository.findById(projectId))
                .thenReturn(Optional.of(new Project("p", "", ownerId)));
        return new SubmitJobRequest(projectId, "text_statistics", JobPriority.NORMAL,
                Map.of("text", "hello taskmesh"), null, null, idempotencyKey);
    }

    private Job job() {
        Job job = new Job(projectId, ownerId, "text_statistics", Map.of("text", "hello"));
        org.springframework.test.util.ReflectionTestUtils.setField(job, "id", UUID.randomUUID());
        return job;
    }
}
