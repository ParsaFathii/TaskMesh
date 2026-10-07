package com.taskmesh.integration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Job submission, idempotency, lifecycle operations and reads.
 */
class JobIntegrationTest extends IntegrationTestBase {

    private String createProject(String username) {
        return JsonPath.<String>read(post(username, "/api/v1/projects",
                Map.of("name", "Jobs Test Project")).getBody(), "$.id");
    }

    private Map<String, Object> validSubmission(String projectId) {
        return Map.of("projectId", projectId, "type", "text_statistics",
                "payload", Map.of("text", "The quick brown fox jumps over the lazy dog"),
                "priority", "HIGH");
    }

    @Test
    void submissionHappyPathCreatesQueuedJobWithAuditRow() {
        String projectId = createProject("user");

        ResponseEntity<String> created = post("user", "/api/v1/jobs", validSubmission(projectId));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String jobId = JsonPath.<String>read(created.getBody(), "$.id");
        assertThat(JsonPath.<String>read(created.getBody(), "$.status")).isEqualTo("QUEUED");
        assertThat(JsonPath.<String>read(created.getBody(), "$.type")).isEqualTo("text_statistics");
        assertThat(JsonPath.<String>read(created.getBody(), "$.priority")).isEqualTo("HIGH");
        assertThat(created.getHeaders().getLocation()).isNotNull();

        Integer auditRows = jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'job.create' AND resource_id = ?",
                Integer.class, jobId);
        assertThat(auditRows).isEqualTo(1);

        ResponseEntity<String> detail = get("user", "/api/v1/jobs/" + jobId);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(detail.getBody(), "$.job.status")).isEqualTo("QUEUED");
        assertThat((Map<String, Object>) JsonPath.read(detail.getBody(), "$.job.payload"))
                .containsKey("text");
    }

    @Test
    void duplicateIdempotencyKeyReplaysTheExistingJob() {
        String projectId = createProject("user");
        Map<String, Object> body = new java.util.HashMap<>(validSubmission(projectId));
        body.put("idempotencyKey", "stable-key-1");

        ResponseEntity<String> first = post("user", "/api/v1/jobs", body);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = post("user", "/api/v1/jobs", body);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(second.getBody(), "$.id"))
                .isEqualTo(JsonPath.<String>read(first.getBody(), "$.id"));

        Integer jobsForProject = jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE idempotency_key = ?", Integer.class, "stable-key-1");
        assertThat(jobsForProject).isEqualTo(1);
    }

    @Test
    void idempotencyKeyHeaderIsHonored() {
        String projectId = createProject("user");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token("user"));
        headers.set("Idempotency-Key", "header-key-1");
        ResponseEntity<String> first = rest.exchange("/api/v1/jobs", HttpMethod.POST,
                new HttpEntity<>(toJson(validSubmission(projectId)), headers), String.class);
        ResponseEntity<String> second = rest.exchange("/api/v1/jobs", HttpMethod.POST,
                new HttpEntity<>(toJson(validSubmission(projectId)), headers), String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(second.getBody(), "$.id"))
                .isEqualTo(JsonPath.<String>read(first.getBody(), "$.id"));
    }

    @Test
    void invalidPayloadShapeIsRejectedBeforePersistence() {
        String projectId = createProject("user");
        Map<String, Object> body = Map.of("projectId", projectId, "type", "image_resize",
                "payload", Map.of("imageBase64", "AAAA", "width", 0, "height", 32));

        ResponseEntity<String> response = post("user", "/api/v1/jobs", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.error.code")).isEqualTo("VALIDATION");
        assertThat(JsonPath.<String>read(response.getBody(), "$.error.message"))
                .contains("width: must be >= 1");
    }

    @Test
    void unknownJobTypeIsRejected() {
        String projectId = createProject("user");

        ResponseEntity<String> response = post("user", "/api/v1/jobs",
                Map.of("projectId", projectId, "type", "run_python",
                        "payload", Map.of("code", "print(1)")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.error.message"))
                .contains("Unknown job type");
    }

    @Test
    void submissionToForeignProjectIsHiddenFromPlainUsers() {
        post("admin", "/api/v1/users", Map.of("username", "mallory", "email",
                "mallory@taskmesh.local", "password", "MalloryPass!9", "role", "USER"));
        String foreignProject = createProject("user");

        ResponseEntity<String> response = post("mallory", "/api/v1/jobs",
                validSubmission(foreignProject));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void cancelQueuedJobFinalizesImmediately() {
        String projectId = createProject("user");
        String jobId = JsonPath.<String>read(post("user", "/api/v1/jobs",
                validSubmission(projectId)).getBody(), "$.id");

        ResponseEntity<String> cancelled = post("user", "/api/v1/jobs/" + jobId + "/cancel", Map.of());

        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(cancelled.getBody(), "$.status")).isEqualTo("CANCELLED");
        assertThat(JsonPath.<Boolean>read(cancelled.getBody(), "$.cancelRequested")).isTrue();

        Integer auditRows = jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'job.cancel' AND resource_id = ?",
                Integer.class, jobId);
        assertThat(auditRows).isEqualTo(1);
    }

    @Test
    void cancelRunningJobOnlySetsTheFlag() {
        String projectId = createProject("user");
        String jobId = JsonPath.<String>read(post("user", "/api/v1/jobs",
                validSubmission(projectId)).getBody(), "$.id");
        jdbc.update("UPDATE jobs SET status = 'RUNNING', started_at = now(), "
                + "lease_expires_at = now() + interval '90 seconds' WHERE id = ?", uuid(jobId));

        ResponseEntity<String> cancelled = post("user", "/api/v1/jobs/" + jobId + "/cancel", Map.of());

        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(cancelled.getBody(), "$.status")).isEqualTo("RUNNING");
        assertThat(JsonPath.<Boolean>read(cancelled.getBody(), "$.cancelRequested")).isTrue();
    }

    @Test
    void finishedJobsCannotBeCancelled() {
        String projectId = createProject("user");
        String jobId = JsonPath.<String>read(post("user", "/api/v1/jobs",
                validSubmission(projectId)).getBody(), "$.id");
        jdbc.update("UPDATE jobs SET status = 'SUCCEEDED', completed_at = now() WHERE id = ?", uuid(jobId));

        ResponseEntity<String> response = post("user", "/api/v1/jobs/" + jobId + "/cancel", Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(JsonPath.<String>read(response.getBody(), "$.error.code")).isEqualTo("CONFLICT");
    }

    @Test
    void retryFailedJobRequeuesIt() {
        String projectId = createProject("user");
        String jobId = JsonPath.<String>read(post("user", "/api/v1/jobs",
                validSubmission(projectId)).getBody(), "$.id");
        jdbc.update("UPDATE jobs SET status = 'FAILED', retry_count = 2, completed_at = now(), "
                + "last_error = 'boom' WHERE id = ?", uuid(jobId));

        ResponseEntity<String> retried = post("user", "/api/v1/jobs/" + jobId + "/retry", Map.of());

        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(retried.getBody(), "$.status")).isEqualTo("QUEUED");
        assertThat((Integer) JsonPath.read(retried.getBody(), "$.retryCount")).isZero();
        assertThat(JsonPath.<String>read(retried.getBody(), "$.lastError")).isEqualTo("boom");

        Integer auditRows = jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'job.retry' AND resource_id = ?",
                Integer.class, jobId);
        assertThat(auditRows).isEqualTo(1);
    }

    @Test
    void queuedJobsCannotBeRetried() {
        String projectId = createProject("user");
        String jobId = JsonPath.<String>read(post("user", "/api/v1/jobs",
                validSubmission(projectId)).getBody(), "$.id");

        ResponseEntity<String> response = post("user", "/api/v1/jobs/" + jobId + "/retry", Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void jobDetailIncludesWorkerSummaryWhenAssigned() {
        String projectId = createProject("user");
        String jobId = JsonPath.<String>read(post("user", "/api/v1/jobs",
                validSubmission(projectId)).getBody(), "$.id");
        String workerId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO workers (id, name, hostname, version, status, capabilities, "
                        + "heartbeat_interval_s) VALUES (?, 'w1', 'host1', '0.1.0', 'IDLE', "
                        + "ARRAY['text_statistics'], 10)",
                uuid(workerId));
        jdbc.update("UPDATE jobs SET status = 'RUNNING', worker_id = ? WHERE id = ?",
                uuid(workerId), uuid(jobId));

        ResponseEntity<String> detail = get("user", "/api/v1/jobs/" + jobId);

        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(detail.getBody(), "$.worker.name")).isEqualTo("w1");
        assertThat(JsonPath.<String>read(detail.getBody(), "$.worker.status")).isEqualTo("IDLE");
    }

    @Test
    void jobsAreInvisibleToForeignUsersButVisibleToOperators() {
        post("admin", "/api/v1/users", Map.of("username", "mallory", "email",
                "mallory@taskmesh.local", "password", "MalloryPass!9", "role", "USER"));
        String projectId = createProject("user");
        String jobId = JsonPath.<String>read(post("user", "/api/v1/jobs",
                validSubmission(projectId)).getBody(), "$.id");

        assertThat(get("mallory", "/api/v1/jobs/" + jobId).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("operator", "/api/v1/jobs/" + jobId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("admin", "/api/v1/jobs/" + jobId).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> malloryList = get("mallory", "/api/v1/jobs");
        assertThat((Integer) JsonPath.read(malloryList.getBody(), "$.total")).isZero();
        ResponseEntity<String> operatorList = get("operator", "/api/v1/jobs");
        assertThat((Integer) JsonPath.read(operatorList.getBody(), "$.total")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void jobListingSupportsStatusAndProjectFilters() {
        String projectId = createProject("user");
        post("user", "/api/v1/jobs", validSubmission(projectId));
        String otherProject = createProject("user");
        post("user", "/api/v1/jobs", validSubmission(otherProject));
        post("user", "/api/v1/jobs", validSubmission(otherProject));

        ResponseEntity<String> byProject = get("user", "/api/v1/jobs?projectId=" + otherProject);
        assertThat((Integer) JsonPath.read(byProject.getBody(), "$.total")).isEqualTo(2);

        String firstId = JsonPath.<String>read(byProject.getBody(), "$.items[0].id");
        jdbc.update("UPDATE jobs SET status = 'RUNNING' WHERE id = ?", uuid(firstId));
        ResponseEntity<String> byStatus = get("user", "/api/v1/jobs?status=RUNNING");
        assertThat((Integer) JsonPath.read(byStatus.getBody(), "$.total")).isEqualTo(1);
        assertThat(JsonPath.<String>read(byStatus.getBody(), "$.items[0].id")).isEqualTo(firstId);
    }

    @Test
    void logsAndAttemptsAreServedNewestFirst() {
        String projectId = createProject("user");
        String jobId = JsonPath.<String>read(post("user", "/api/v1/jobs",
                validSubmission(projectId)).getBody(), "$.id");
        jdbc.update("INSERT INTO job_logs (job_id, level, message) VALUES (?, 'INFO', 'first')", uuid(jobId));
        jdbc.update("INSERT INTO job_logs (job_id, level, message) VALUES (?, 'INFO', 'second')", uuid(jobId));
        jdbc.update("INSERT INTO job_logs (job_id, level, message) VALUES (?, 'WARN', 'warn line')", uuid(jobId));
        jdbc.update("INSERT INTO job_attempts (job_id, attempt_number, started_at, outcome) "
                + "VALUES (?, 1, now(), 'FAILED')", uuid(jobId));

        ResponseEntity<String> logs = get("user", "/api/v1/jobs/" + jobId + "/logs");
        assertThat(logs.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> messages = JsonPath.read(logs.getBody(), "$[*].message");
        assertThat(messages).containsExactly("warn line", "second", "first");

        ResponseEntity<String> warnOnly = get("user", "/api/v1/jobs/" + jobId + "/logs?level=WARN");
        assertThat(JsonPath.<List<String>>read(warnOnly.getBody(), "$[*].message"))
                .containsExactly("warn line");

        ResponseEntity<String> limited = get("user", "/api/v1/jobs/" + jobId + "/logs?limit=1");
        assertThat(JsonPath.<List<String>>read(limited.getBody(), "$[*].message"))
                .containsExactly("warn line");

        ResponseEntity<String> attempts = get("user", "/api/v1/jobs/" + jobId + "/attempts");
        assertThat(attempts.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) JsonPath.read(attempts.getBody(), "$[0].attemptNumber")).isEqualTo(1);
        assertThat(JsonPath.<String>read(attempts.getBody(), "$[0].outcome")).isEqualTo("FAILED");
    }

    @Test
    void jobTypeCatalogIsServedToAnyAuthenticatedUser() {
        ResponseEntity<String> response = get("user", "/api/v1/job-types");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> types = JsonPath.read(response.getBody(), "$[*].type");
        assertThat(types).containsExactlyInAnyOrder("csv_analysis", "json_transform",
                "image_resize", "hash_sha256", "text_statistics", "archive_inspection",
                "cpu_benchmark");
        Map<String, Object> schema = JsonPath.read(response.getBody(), "$[0].payloadSchema");
        assertThat(schema).containsKeys("type", "properties", "required");
    }
}
