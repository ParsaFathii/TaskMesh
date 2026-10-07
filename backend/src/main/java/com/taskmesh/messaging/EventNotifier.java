package com.taskmesh.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskmesh.config.TaskMeshProperties;
import com.taskmesh.domain.JobStatus;
import com.taskmesh.domain.WorkerStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes compact NOTIFY payloads on the {@code taskmesh_events} channel (SPEC §8).
 * Called inside the service transaction so events are only delivered when the
 * transaction commits; the PostgreSQL listener thread (workers and this backend) picks
 * them up, enriches them and fans them out to WebSocket clients.
 */
@Component
public class EventNotifier {

    private static final Logger log = LoggerFactory.getLogger(EventNotifier.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String channel;

    public EventNotifier(JdbcTemplate jdbc, ObjectMapper objectMapper, TaskMeshProperties properties) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.channel = properties.eventChannel();
    }

    public void jobUpdated(UUID jobId, JobStatus status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("t", "job");
        payload.put("id", jobId.toString());
        payload.put("s", status.name());
        notify(payload);
    }

    public void queueEvent(UUID jobId, String event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("t", "queue");
        payload.put("e", event);
        payload.put("id", jobId.toString());
        notify(payload);
    }

    public void workerUpdated(UUID workerId, WorkerStatus status, UUID currentJobId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("t", "worker");
        payload.put("id", workerId.toString());
        payload.put("s", status.name());
        payload.put("j", currentJobId == null ? null : currentJobId.toString());
        notify(payload);
    }

    private void notify(Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            // pg_notify returns a single-row result; query (not update) so the driver
            // does not reject the statement for producing a result set.
            jdbc.query("SELECT pg_notify(?, ?)", rs -> { }, channel, json);
        } catch (JsonProcessingException | DataAccessException e) {
            // Event delivery is best-effort; the state change itself is already persisted.
            log.warn("Could not publish event on {}: {}", channel, e.getMessage());
        }
    }
}
