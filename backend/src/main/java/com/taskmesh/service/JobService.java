package com.taskmesh.service;

import com.taskmesh.catalog.JobTypeCatalog;
import com.taskmesh.controller.ApiException;
import com.taskmesh.controller.dto.SubmitJobRequest;
import com.taskmesh.domain.Job;
import com.taskmesh.domain.JobPriority;
import com.taskmesh.domain.JobStatus;
import com.taskmesh.domain.JobAttempt;
import com.taskmesh.domain.JobLog;
import com.taskmesh.domain.JobResult;
import com.taskmesh.domain.LogLevel;
import com.taskmesh.domain.Worker;
import com.taskmesh.messaging.EventNotifier;
import com.taskmesh.repository.JobRepository;
import com.taskmesh.repository.JobSpecifications;
import com.taskmesh.repository.ProjectRepository;
import com.taskmesh.repository.WorkerRepository;
import com.taskmesh.repository.JobAttemptRepository;
import com.taskmesh.repository.JobLogRepository;
import com.taskmesh.repository.JobResultRepository;
import com.taskmesh.security.AuthenticatedUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Job submission, lifecycle operations and reads.
 *
 * <ul>
 *   <li>Idempotency: a submission carrying an idempotency key that already exists for
 *       the same owner returns the stored job (HTTP 200) instead of creating a duplicate.</li>
 *   <li>State changes are validated by the {@link JobStateMachine}.</li>
 *   <li>Access: USER sees only their own jobs; OPERATOR and ADMIN see all.</li>
 * </ul>
 */
@Service
public class JobService {

    private final JobRepository jobRepository;
    private final ProjectRepository projectRepository;
    private final WorkerRepository workerRepository;
    private final JobLogRepository jobLogRepository;
    private final JobAttemptRepository jobAttemptRepository;
    private final JobResultRepository jobResultRepository;
    private final JobTypeCatalog catalog;
    private final JobStateMachine stateMachine;
    private final AuditService auditService;
    private final EventNotifier notifier;

    public JobService(JobRepository jobRepository, ProjectRepository projectRepository,
                      WorkerRepository workerRepository, JobLogRepository jobLogRepository,
                      JobAttemptRepository jobAttemptRepository, JobResultRepository jobResultRepository,
                      JobTypeCatalog catalog, JobStateMachine stateMachine,
                      AuditService auditService, EventNotifier notifier) {
        this.jobRepository = jobRepository;
        this.projectRepository = projectRepository;
        this.workerRepository = workerRepository;
        this.jobLogRepository = jobLogRepository;
        this.jobAttemptRepository = jobAttemptRepository;
        this.jobResultRepository = jobResultRepository;
        this.catalog = catalog;
        this.stateMachine = stateMachine;
        this.auditService = auditService;
        this.notifier = notifier;
    }

    /** Submission outcome; {@code duplicate} marks an idempotent replay (HTTP 200). */
    public record Submission(Job job, boolean duplicate) {
    }

    public Submission submit(AuthenticatedUser actor, SubmitJobRequest request, String headerIdempotencyKey) {
        projectRepository.findById(request.projectId())
                .filter(project -> actor.isOperatorOrAdmin()
                        || project.getOwnerId().equals(actor.id()))
                .orElseThrow(() -> new ApiException.NotFound("Project not found"));
        catalog.validatePayload(request.type(), request.payload());

        String idempotencyKey = firstNonBlank(request.idempotencyKey(), headerIdempotencyKey);
        if (idempotencyKey != null) {
            var existing = jobRepository.findByOwnerIdAndIdempotencyKey(actor.id(), idempotencyKey);
            if (existing.isPresent()) {
                return new Submission(existing.get(), true);
            }
        }

        Job job = new Job(request.projectId(), actor.id(), request.type(), request.payload());
        job.setPriority(request.priority() == null ? JobPriority.NORMAL : request.priority());
        job.setMaxRetries(request.maxRetries() == null ? 3 : request.maxRetries());
        job.setTimeoutSeconds(request.timeoutSeconds() == null ? 120 : request.timeoutSeconds());
        job.setIdempotencyKey(idempotencyKey);
        try {
            job = jobRepository.saveAndFlush(job);
        } catch (DataIntegrityViolationException e) {
            // Lost a race against a concurrent duplicate idempotency key: return theirs.
            var winner = jobRepository.findByOwnerIdAndIdempotencyKey(actor.id(), idempotencyKey);
            if (winner.isPresent()) {
                return new Submission(winner.get(), true);
            }
            throw e;
        }
        auditService.success(actor.id(), actor.username(), "job.create", "job",
                job.getId().toString(),
                Map.of("type", job.getType(), "projectId", job.getProjectId().toString()));
        notifier.queueEvent(job.getId(), "job.enqueued");
        return new Submission(job, false);
    }

    public Job requireVisible(UUID jobId, AuthenticatedUser actor) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ApiException.NotFound("Job not found"));
        if (!actor.isOperatorOrAdmin() && !job.getOwnerId().equals(actor.id())) {
            throw new ApiException.NotFound("Job not found");
        }
        return job;
    }

    /** Worker summary for GET /api/v1/jobs/{id}. */
    public Worker workerOf(Job job) {
        return job.getWorkerId() == null ? null : workerRepository.findById(job.getWorkerId()).orElse(null);
    }

    public Page<Job> listFor(AuthenticatedUser actor, UUID projectId, JobStatus status, String type,
                             JobPriority priority, Pageable pageable) {
        return jobRepository.findAll(allOf(
                JobSpecifications.visibleTo(actor),
                projectId == null ? null : JobSpecifications.projectId(projectId),
                status == null ? null : JobSpecifications.status(status),
                type == null || type.isBlank() ? null : JobSpecifications.type(type),
                priority == null ? null : JobSpecifications.priority(priority)), pageable);
    }

    /**
     * Cancel a job: QUEUED/RETRYING jobs become CANCELLED immediately; RUNNING jobs get
     * the {@code cancel_requested} flag and are finalized by the worker or the sweeper.
     */
    public Job cancel(AuthenticatedUser actor, UUID jobId) {
        Job job = requireVisible(jobId, actor);
        switch (job.getStatus()) {
            case QUEUED, RETRYING -> {
                String from = job.getStatus().name();
                stateMachine.assertTransition(job.getStatus(), JobStatus.CANCELLED);
                job.setStatus(JobStatus.CANCELLED);
                job.setCancelRequested(true);
                job.setCompletedAt(now());
                job.setLeaseExpiresAt(null);
                job = jobRepository.save(job);
                auditService.success(actor.id(), actor.username(), "job.cancel", "job",
                        jobId.toString(), Map.of("from", from, "to", "CANCELLED"));
                notifier.jobUpdated(jobId, JobStatus.CANCELLED);
            }
            case RUNNING -> {
                job.setCancelRequested(true);
                job = jobRepository.save(job);
                auditService.success(actor.id(), actor.username(), "job.cancel", "job",
                        jobId.toString(), Map.of("requested", true, "status", "RUNNING"));
            }
            default -> throw new ApiException.Conflict(
                    "Job cannot be cancelled from status " + job.getStatus());
        }
        return job;
    }

    /** Manual retry: FAILED/TIMED_OUT/CANCELLED jobs are re-queued with a clean slate. */
    public Job retry(AuthenticatedUser actor, UUID jobId) {
        Job job = requireVisible(jobId, actor);
        stateMachine.assertTransition(job.getStatus(), JobStatus.QUEUED);
        job.setStatus(JobStatus.QUEUED);
        job.setRetryCount(0);
        job.setQueuedAt(now());
        job.setAvailableAt(now());
        job.setStartedAt(null);
        job.setCompletedAt(null);
        job.setWorkerId(null);
        job.setProgress(null);
        job.setCancelRequested(false);
        job.setLeaseExpiresAt(null);
        job = jobRepository.save(job);
        auditService.success(actor.id(), actor.username(), "job.retry", "job",
                jobId.toString(), Map.of("to", "QUEUED"));
        notifier.queueEvent(jobId, "job.requeued");
        notifier.jobUpdated(jobId, JobStatus.QUEUED);
        return job;
    }

    public java.util.List<JobLog> logs(AuthenticatedUser actor, UUID jobId, LogLevel level, int limit) {
        requireVisible(jobId, actor);
        var page = org.springframework.data.domain.PageRequest.of(0, limit);
        return level == null
                ? jobLogRepository.findNewestFirst(jobId, page)
                : jobLogRepository.findNewestFirst(jobId, level, page);
    }

    public java.util.List<JobAttempt> attempts(AuthenticatedUser actor, UUID jobId) {
        requireVisible(jobId, actor);
        return jobAttemptRepository.findByJobIdOrderByAttemptNumberDesc(jobId);
    }

    public JobResult result(AuthenticatedUser actor, UUID jobId) {
        requireVisible(jobId, actor);
        return jobResultRepository.findByJobId(jobId)
                .orElseThrow(() -> new ApiException.NotFound("No result available for this job"));
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    /** Null-tolerant Specification concatenation (skips inactive filters). */
    @SafeVarargs
    private static <T> org.springframework.data.jpa.domain.Specification<T> allOf(
            org.springframework.data.jpa.domain.Specification<T>... specs) {
        org.springframework.data.jpa.domain.Specification<T> combined = null;
        for (org.springframework.data.jpa.domain.Specification<T> spec : specs) {
            if (spec != null) {
                combined = combined == null ? spec : combined.and(spec);
            }
        }
        return combined;
    }
}
