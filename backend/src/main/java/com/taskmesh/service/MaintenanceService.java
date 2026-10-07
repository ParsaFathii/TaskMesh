package com.taskmesh.service;

import com.taskmesh.domain.Job;
import com.taskmesh.domain.JobAttempt;
import com.taskmesh.domain.JobStatus;
import com.taskmesh.domain.Worker;
import com.taskmesh.domain.WorkerStatus;
import com.taskmesh.messaging.EventNotifier;
import com.taskmesh.repository.JobAttemptRepository;
import com.taskmesh.repository.JobRepository;
import com.taskmesh.repository.WorkerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Background maintenance (SPEC §7), scheduled every 5 seconds:
 * <ul>
 *   <li>jobs RUNNING with an expired lease are retried (ABANDONED) or finalized as
 *       TIMED_OUT / FAILED when retries are exhausted</li>
 *   <li>cancellations of unresponsive workers are finalized after lease expiry plus 30s</li>
 *   <li>workers silent for more than three heartbeat intervals are marked OFFLINE</li>
 * </ul>
 *
 * <p>The sweep logic runs in one transaction per cycle so that job rows, attempt rows
 * and NOTIFY events commit atomically. Illegal transitions found mid-sweep (e.g. a job
 * that was just claimed again) are logged and skipped, never fatal.</p>
 */
@Service
public class MaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);
    private static final int CANCEL_GRACE_SECONDS = 30;
    private static final int HARD_TIMEOUT_MARGIN_SECONDS = 60;

    private final JdbcTemplate jdbc;
    private final JobRepository jobRepository;
    private final JobAttemptRepository attemptRepository;
    private final WorkerRepository workerRepository;
    private final JobStateMachine stateMachine;
    private final EventNotifier notifier;

    public MaintenanceService(JdbcTemplate jdbc, JobRepository jobRepository,
                              JobAttemptRepository attemptRepository, WorkerRepository workerRepository,
                              JobStateMachine stateMachine, EventNotifier notifier) {
        this.jdbc = jdbc;
        this.jobRepository = jobRepository;
        this.attemptRepository = attemptRepository;
        this.workerRepository = workerRepository;
        this.stateMachine = stateMachine;
        this.notifier = notifier;
    }

    @Scheduled(fixedDelay = 5000)
    public void sweep() {
        sweepOnce();
    }

    /** One maintenance pass; public so tests can invoke it deterministically. */
    @Transactional
    public void sweepOnce() {
        finalizeExpiredLeases();
        markStaleWorkersOffline();
    }

    private void finalizeExpiredLeases() {
        List<UUID> victims = jdbc.query("""
                SELECT id FROM jobs
                WHERE status = 'RUNNING' AND lease_expires_at IS NOT NULL AND lease_expires_at < now()
                """, (rs, i) -> UUID.fromString(rs.getString(1)));
        for (UUID jobId : victims) {
            try {
                jobRepository.findById(jobId).ifPresent(this::finalizeExpiredJob);
            } catch (Exception e) {
                // A concurrent worker claim can make a planned transition illegal; skip it.
                log.warn("Sweeper skipped job {}: {}", jobId, e.getMessage());
            }
        }
    }

    private void finalizeExpiredJob(Job job) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (job.isCancelRequested()) {
            OffsetDateTime graceDeadline = job.getLeaseExpiresAt()
                    .plusSeconds(CANCEL_GRACE_SECONDS);
            if (now.isAfter(graceDeadline)) {
                transition(job, JobStatus.CANCELLED, JobAttempt.Outcome.CANCELLED, null, now);
            }
            return;
        }
        boolean hardTimeoutExceeded = job.getStartedAt() != null
                && job.getStartedAt()
                .plusSeconds(job.getTimeoutSeconds() + (long) HARD_TIMEOUT_MARGIN_SECONDS)
                .isBefore(now);
        if (hardTimeoutExceeded) {
            if (job.getRetryCount() < job.getMaxRetries()) {
                requeue(job, JobAttempt.Outcome.TIMED_OUT,
                        "Lease expired and hard timeout exceeded", now);
            } else {
                transition(job, JobStatus.TIMED_OUT, JobAttempt.Outcome.TIMED_OUT,
                        "Timed out: lease expired and hard timeout exceeded", now);
            }
        } else {
            // Lease expired but the job may still finish: treat as crashed worker.
            if (job.getRetryCount() < job.getMaxRetries()) {
                requeue(job, JobAttempt.Outcome.ABANDONED,
                        "Worker lease expired (worker crashed)", now);
            } else {
                transition(job, JobStatus.FAILED, JobAttempt.Outcome.ABANDONED,
                        "Worker lease expired and retries exhausted", now);
            }
        }
    }

    /** RUNNING -> RETRYING with exponential backoff (attempt closed with the given outcome). */
    private void requeue(Job job, JobAttempt.Outcome outcome, String error, OffsetDateTime now) {
        stateMachine.assertTransition(job.getStatus(), JobStatus.RETRYING);
        closeAttempts(job, outcome, error);
        int nextRetryCount = job.getRetryCount() + 1;
        job.setStatus(JobStatus.RETRYING);
        job.setRetryCount(nextRetryCount);
        job.setWorkerId(null);
        job.setLeaseExpiresAt(null);
        job.setProgress(null);
        job.setLastError(error);
        job.setAvailableAt(now.plusSeconds(JobStateMachine.backoffSeconds(nextRetryCount)));
        jobRepository.save(job);
        notifier.jobUpdated(job.getId(), JobStatus.RETRYING);
        notifier.queueEvent(job.getId(), "job.requeued");
        log.info("Job {} re-queued after lease expiry (attempt outcome {}, retry {})",
                job.getId(), outcome, nextRetryCount);
    }

    private void transition(Job job, JobStatus to, JobAttempt.Outcome outcome, String lastError,
                            OffsetDateTime now) {
        stateMachine.assertTransition(job.getStatus(), to);
        closeAttempts(job, outcome, lastError);
        job.setStatus(to);
        job.setCompletedAt(now);
        job.setLeaseExpiresAt(null);
        if (lastError != null) {
            job.setLastError(lastError);
        }
        jobRepository.save(job);
        notifier.jobUpdated(job.getId(), to);
        log.info("Job {} transitioned to {} by sweeper", job.getId(), to);
    }

    private void closeAttempts(Job job, JobAttempt.Outcome outcome, String error) {
        List<JobAttempt> open = attemptRepository.findByJobIdAndFinishedAtIsNull(job.getId());
        if (open.isEmpty()) {
            // The worker crashed before writing an attempt row; record a synthetic one.
            JobAttempt attempt = new JobAttempt(job.getId(), job.getRetryCount() + 1,
                    job.getWorkerId(), job.getStartedAt());
            attempt.finish(outcome, error);
            attemptRepository.save(attempt);
        } else {
            open.forEach(attempt -> attempt.finish(outcome, error));
            attemptRepository.saveAll(open);
        }
    }

    private void markStaleWorkersOffline() {
        List<UUID> stale = jdbc.query("""
                SELECT id FROM workers
                WHERE status <> 'OFFLINE'
                  AND last_heartbeat < now() - make_interval(secs => 3 * heartbeat_interval_s)
                """, (rs, i) -> UUID.fromString(rs.getString(1)));
        for (UUID workerId : stale) {
            workerRepository.findById(workerId).ifPresent(worker -> {
                worker.setStatus(WorkerStatus.OFFLINE);
                workerRepository.save(worker);
                notifier.workerUpdated(workerId, WorkerStatus.OFFLINE, worker.getCurrentJobId());
                log.info("Worker {} marked OFFLINE (no heartbeat for 3 intervals)", workerId);
            });
        }
    }
}
