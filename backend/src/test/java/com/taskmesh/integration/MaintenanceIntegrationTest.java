package com.taskmesh.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.taskmesh.service.MaintenanceService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Maintenance sweeper: lease expiry handling, cancellation finalization and stale
 * worker detection (SPEC §7). Invoked synchronously for determinism.
 */
class MaintenanceIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MaintenanceService maintenance;

    private String createRunningJob(int leaseAgeSeconds, int startedAgeSeconds, int timeoutSeconds) {
        String projectId = jdbc.queryForObject(
                "INSERT INTO projects (name, owner_id) VALUES ('sweeper project', "
                        + "(SELECT id FROM users WHERE username = 'user')) RETURNING id",
                String.class);
        return jdbc.queryForObject("""
                INSERT INTO jobs (project_id, owner_id, type, payload, status, started_at,
                    timeout_seconds, lease_expires_at, retry_count, max_retries)
                VALUES (?, (SELECT id FROM users WHERE username = 'user'), 'text_statistics',
                    '{}'::jsonb, 'RUNNING',
                    now() - make_interval(secs => ?), ?, now() - make_interval(secs => ?), 0, 3)
                RETURNING id::text
                """, String.class, uuid(projectId), startedAgeSeconds, timeoutSeconds, leaseAgeSeconds);
    }

    @Test
    void expiredLeaseFromCrashedWorkerRequeuesWithBackoff() {
        String jobId = createRunningJob(30, 20, 120);

        maintenance.sweepOnce();

        String status = jobStatus(jobId);
        assertThat(status).isEqualTo("RETRYING");
        Integer retryCount = jdbc.queryForObject(
                "SELECT retry_count FROM jobs WHERE id = ?", Integer.class, uuid(jobId));
        assertThat(retryCount).isEqualTo(1);

        String availableAt = jdbc.queryForObject(
                "SELECT extract(epoch from available_at - now())::int FROM jobs WHERE id = ?",
                String.class, uuid(jobId));
        assertThat(Integer.parseInt(availableAt))
                .as("backoff for first retry is 2^1*5 = 10s")
                .isBetween(5, 11);

        String attemptOutcome = jdbc.queryForObject(
                "SELECT outcome FROM job_attempts WHERE job_id = ? ORDER BY id DESC LIMIT 1",
                String.class, uuid(jobId));
        assertThat(attemptOutcome).isEqualTo("ABANDONED");
        String lastError = jdbc.queryForObject(
                "SELECT last_error FROM jobs WHERE id = ?", String.class, uuid(jobId));
        assertThat(lastError).contains("lease expired");
    }

    @Test
    void hardTimeoutWithExhaustedRetriesTimesTheJobOut() {
        String jobId = createRunningJob(60, 1000, 120);
        jdbc.update("UPDATE jobs SET retry_count = 3 WHERE id = ?", uuid(jobId));

        maintenance.sweepOnce();

        assertThat(jobStatus(jobId)).isEqualTo("TIMED_OUT");
        Integer completed = jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE id = ? AND completed_at IS NOT NULL",
                Integer.class, uuid(jobId));
        assertThat(completed).isEqualTo(1);
    }

    @Test
    void crashedWorkerWithExhaustedRetriesFailsTheJob() {
        String jobId = createRunningJob(30, 20, 120);
        jdbc.update("UPDATE jobs SET retry_count = 3 WHERE id = ?", uuid(jobId));

        maintenance.sweepOnce();

        assertThat(jobStatus(jobId)).isEqualTo("FAILED");
        String lastError = jdbc.queryForObject(
                "SELECT last_error FROM jobs WHERE id = ?", String.class, uuid(jobId));
        assertThat(lastError).contains("retries exhausted");
    }

    @Test
    void requestedCancellationIsFinalizedAfterGracePeriod() {
        String jobId = createRunningJob(40, 30, 120);
        jdbc.update("UPDATE jobs SET cancel_requested = true WHERE id = ?", uuid(jobId));

        maintenance.sweepOnce();

        assertThat(jobStatus(jobId)).isEqualTo("CANCELLED");
        String outcome = jdbc.queryForObject(
                "SELECT outcome FROM job_attempts WHERE job_id = ?", String.class, uuid(jobId));
        assertThat(outcome).isEqualTo("CANCELLED");
    }

    @Test
    void cancellationWithinGracePeriodKeepsTheJobRunning() {
        String jobId = createRunningJob(10, 5, 120);
        jdbc.update("UPDATE jobs SET cancel_requested = true WHERE id = ?", uuid(jobId));

        maintenance.sweepOnce();

        assertThat(jobStatus(jobId)).isEqualTo("RUNNING");
    }

    @Test
    void jobsWithLiveLeasesAreUntouched() {
        String projectId = jdbc.queryForObject(
                "INSERT INTO projects (name, owner_id) VALUES ('live lease project', "
                        + "(SELECT id FROM users WHERE username = 'user')) RETURNING id",
                String.class);
        String jobId = jdbc.queryForObject("""
                INSERT INTO jobs (project_id, owner_id, type, payload, status, started_at,
                    lease_expires_at)
                VALUES (?, (SELECT id FROM users WHERE username = 'user'), 'text_statistics',
                    '{}'::jsonb, 'RUNNING', now(), now() + interval '60 seconds')
                RETURNING id::text
                """, String.class, uuid(projectId));

        maintenance.sweepOnce();

        assertThat(jobStatus(jobId)).isEqualTo("RUNNING");
    }

    @Test
    void silentWorkersAreMarkedOffline() {
        String workerId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO workers (id, name, hostname, version, status, heartbeat_interval_s,
                    last_heartbeat)
                VALUES (?, 'silent-worker', 'host1', '0.1.0', 'IDLE', 10,
                    now() - make_interval(secs => 60))
                """, uuid(workerId));
        String healthyWorker = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO workers (id, name, hostname, version, status, heartbeat_interval_s)
                VALUES (?, 'healthy-worker', 'host1', '0.1.0', 'IDLE', 10)
                """, uuid(healthyWorker));

        maintenance.sweepOnce();

        String silentStatus = workerStatus(workerId);
        assertThat(silentStatus).isEqualTo("OFFLINE");
        assertThat(workerStatus(healthyWorker)).isEqualTo("IDLE");
    }

    private String jobStatus(String jobId) {
        return jdbc.queryForObject("SELECT status::text FROM jobs WHERE id = ?",
                String.class, uuid(jobId));
    }

    private String workerStatus(String workerId) {
        return jdbc.queryForObject("SELECT status::text FROM workers WHERE id = ?",
                String.class, uuid(workerId));
    }
}
