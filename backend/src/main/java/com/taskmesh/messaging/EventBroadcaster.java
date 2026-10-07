package com.taskmesh.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskmesh.domain.WorkerStatus;
import com.taskmesh.repository.JobRepository;
import com.taskmesh.repository.WorkerRepository;
import com.taskmesh.websocket.EventsWebSocket;
import jakarta.websocket.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fan-out hub for realtime events. The {@link PgListener} feeds it NOTIFY payloads
 * (emitted by workers or by this backend); each payload is enriched from the database
 * and pushed as a JSON text frame (SPEC §8) to every authenticated WebSocket session.
 */
@Component
public class EventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(EventBroadcaster.class);

    private final Map<String, EventsWebSocket.Client> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final JobRepository jobRepository;
    private final WorkerRepository workerRepository;

    public EventBroadcaster(ObjectMapper objectMapper, JobRepository jobRepository,
                            WorkerRepository workerRepository) {
        this.objectMapper = objectMapper;
        this.jobRepository = jobRepository;
        this.workerRepository = workerRepository;
    }

    public void register(EventsWebSocket.Client client) {
        sessions.put(client.session().getId(), client);
        log.debug("WS client connected ({} online)", sessions.size());
    }

    public void unregister(String sessionId) {
        sessions.remove(sessionId);
    }

    public int sessionCount() {
        return sessions.size();
    }

    /**
     * Translates one NOTIFY payload into an enriched WebSocket frame and broadcasts it.
     * Unknown or malformed payloads are logged and dropped, never propagated to clients.
     */
    public void handleNotification(String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            String type = node.path("t").asText("");
            Map<String, Object> frame = switch (type) {
                case "job" -> jobFrame(node);
                case "joblog" -> jobLogFrame(node);
                case "worker" -> workerFrame(node);
                case "queue" -> queueFrame(node);
                default -> null;
            };
            if (frame != null) {
                broadcast(objectMapper.writeValueAsString(frame));
            }
        } catch (Exception e) {
            log.warn("Dropping malformed NOTIFY payload: {}", e.getMessage());
        }
    }

    private Map<String, Object> jobFrame(JsonNode node) {
        UUID jobId = UUID.fromString(node.path("id").asText());
        return jobRepository.findById(jobId)
                .map(job -> {
                    Map<String, Object> frame = frame("job.updated");
                    frame.put("jobId", jobId.toString());
                    frame.put("status", job.getStatus().name());
                    frame.put("progress", job.getProgress());
                    frame.put("workerId", job.getWorkerId() == null ? null : job.getWorkerId().toString());
                    return frame;
                })
                .orElse(null);
    }

    private Map<String, Object> jobLogFrame(JsonNode node) {
        Map<String, Object> frame = frame("job.log");
        frame.put("jobId", node.path("id").asText());
        frame.put("level", node.path("l").asText());
        frame.put("message", node.path("m").asText());
        return frame;
    }

    private Map<String, Object> workerFrame(JsonNode node) {
        UUID workerId = UUID.fromString(node.path("id").asText());
        return workerRepository.findById(workerId)
                .<Map<String, Object>>map(worker -> {
                    Map<String, Object> frame = frame("worker.updated");
                    frame.put("workerId", workerId.toString());
                    frame.put("status", worker.getStatus().name());
                    frame.put("currentJobId", worker.getCurrentJobId() == null
                            ? null : worker.getCurrentJobId().toString());
                    return frame;
                })
                .orElseGet(() -> {
                    // Worker row removed; report the last known state from the payload.
                    Map<String, Object> frame = frame("worker.updated");
                    frame.put("workerId", workerId.toString());
                    frame.put("status", node.path("s").asText(WorkerStatus.OFFLINE.name()));
                    return frame;
                });
    }

    private Map<String, Object> queueFrame(JsonNode node) {
        Map<String, Object> frame = frame("queue.event");
        frame.put("event", node.path("e").asText());
        frame.put("jobId", node.path("id").asText());
        return frame;
    }

    private Map<String, Object> frame(String type) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type);
        frame.put("timestamp", OffsetDateTime.now(ZoneOffset.UTC));
        return frame;
    }

    /** Sends one text frame to every connected session. */
    public void broadcast(String json) {
        sessions.values().forEach(client -> send(client.session(), json));
    }

    private void send(Session session, String json) {
        try {
            // Tomcat rejects concurrent writes on one session; serialize per session.
            synchronized (session) {
                session.getBasicRemote().sendText(json);
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("Failed to deliver event to session {}: {}", session.getId(), e.getMessage());
        }
    }
}
