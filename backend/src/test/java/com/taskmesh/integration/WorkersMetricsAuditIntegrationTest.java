package com.taskmesh.integration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Worker registry, metrics aggregation and audit trail reads.
 */
class WorkersMetricsAuditIntegrationTest extends IntegrationTestBase {

    private String createJob(String username) {
        String projectId = JsonPath.<String>read(post(username, "/api/v1/projects",
                Map.of("name", "Metrics Project")).getBody(), "$.id");
        return JsonPath.<String>read(post(username, "/api/v1/jobs", Map.of(
                "projectId", projectId, "type", "text_statistics",
                "payload", Map.of("text", "metrics content"))).getBody(), "$.id");
    }

    private String insertWorker(String name, int heartbeatAgeSeconds, int intervalSeconds) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO workers (id, name, hostname, version, status, capabilities,
                    last_heartbeat, heartbeat_interval_s, control_port)
                VALUES (?, ?, 'host1', '0.1.0', 'IDLE', ARRAY['text_statistics'],
                    now() - make_interval(secs => ?), ?, 9101)
                """, uuid(id), name, heartbeatAgeSeconds, intervalSeconds);
        return id;
    }

    @Test
    void workerListingComputesStaleness() {
        String freshWorker = insertWorker("fresh-worker", 1, 10);
        String staleWorker = insertWorker("stale-worker", 120, 10);

        ResponseEntity<String> response = get("operator", "/api/v1/workers");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = JsonPath.read(response.getBody(), "$.items[*]");
        assertThat(items).hasSize(2);
        Map<String, Object> fresh = items.stream()
                .filter(w -> w.get("name").equals("fresh-worker")).findFirst().orElseThrow();
        Map<String, Object> stale = items.stream()
                .filter(w -> w.get("name").equals("stale-worker")).findFirst().orElseThrow();
        assertThat((Boolean) fresh.get("stale")).isFalse();
        assertThat((Boolean) stale.get("stale")).isTrue();
        assertThat((List<String>) fresh.get("capabilities")).containsExactly("text_statistics");

        ResponseEntity<String> detail = get("operator", "/api/v1/workers/" + freshWorker);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(detail.getBody(), "$.name")).isEqualTo("fresh-worker");
        assertThat((Integer) JsonPath.read(detail.getBody(), "$.controlPort")).isEqualTo(9101);

        assertThat(get("operator", "/api/v1/workers/" + UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void metricsReportJobsWorkersAndQueueDepth() {
        String jobId = createJob("user");
        insertWorker("metrics-worker", 0, 10);
        jdbc.update("UPDATE jobs SET status = 'RUNNING' WHERE id = ?", uuid(jobId));

        ResponseEntity<String> response = get("operator", "/api/v1/metrics");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) JsonPath.read(response.getBody(), "$.jobsByStatus.RUNNING"))
                .isEqualTo(1);
        assertThat((Integer) JsonPath.read(response.getBody(), "$.queueDepthByPriority.NORMAL"))
                .isEqualTo(0);
        assertThat((Integer) JsonPath.read(response.getBody(), "$.workersByStatus.IDLE"))
                .isEqualTo(1);
        assertThat((Integer) JsonPath.read(response.getBody(), "$.succeededLast24h.count"))
                .isEqualTo(0);
        assertThat((Double) JsonPath.read(response.getBody(), "$.completedPerHourLast24h"))
                .isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void auditTrailContainsAuthAndJobCreationEntries() {
        createJob("user");

        ResponseEntity<String> logins = get("operator", "/api/v1/logs?action=auth.login");
        assertThat(logins.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> loginRows = JsonPath.read(logins.getBody(), "$.items[*]");
        assertThat(loginRows).isNotEmpty();
        assertThat(loginRows.stream().allMatch(row ->
                "SUCCESS".equals(row.get("result")) || "FAILURE".equals(row.get("result")))).isTrue();

        ResponseEntity<String> jobRows = get("operator", "/api/v1/logs?action=job.create");
        List<Map<String, Object>> creations = JsonPath.read(jobRows.getBody(), "$.items[*]");
        assertThat(creations).hasSize(1);
        assertThat(creations.get(0).get("action")).isEqualTo("job.create");
        assertThat(creations.get(0).get("actorName")).isEqualTo("user");
        Map<String, Object> metadata = JsonPath.read(jobRows.getBody(), "$.items[0].metadata");
        assertThat(metadata).containsKey("type");

        ResponseEntity<String> byActor = get("operator", "/api/v1/logs?actor=user");
        assertThat((Integer) JsonPath.read(byActor.getBody(), "$.total")).isGreaterThanOrEqualTo(1);

        // Audit rows must never contain password material.
        Integer passwordLeaks = jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE metadata::text LIKE '%password%' "
                        + "OR metadata::text LIKE '%token%'", Integer.class);
        assertThat(passwordLeaks).isZero();
    }

    @Test
    void auditLogPaginationFollowsTheConvention() {
        for (int i = 0; i < 3; i++) {
            createJob("user");
        }
        ResponseEntity<String> pageOne = get("operator", "/api/v1/logs?action=job.create&page=0&size=2");
        assertThat((Integer) JsonPath.read(pageOne.getBody(), "$.size")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(pageOne.getBody(), "$.total")).isEqualTo(3);
        assertThat((List<?>) JsonPath.read(pageOne.getBody(), "$.items[*]")).hasSize(2);

        ResponseEntity<String> pageTwo = get("operator", "/api/v1/logs?action=job.create&page=1&size=2");
        assertThat((List<?>) JsonPath.read(pageTwo.getBody(), "$.items[*]")).hasSize(1);
    }
}
