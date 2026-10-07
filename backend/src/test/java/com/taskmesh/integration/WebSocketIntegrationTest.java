package com.taskmesh.integration;

import com.jayway.jsonpath.JsonPath;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Realtime event WebSocket: JWT query-parameter authentication, hello frame and
 * end-to-end event delivery (REST submit -> NOTIFY -> listener -> broadcast).
 */
class WebSocketIntegrationTest extends IntegrationTestBase {

    @ClientEndpoint
    public static class CollectingClient {

        final List<String> messages = new CopyOnWriteArrayList<>();
        final CountDownLatch anyMessage = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        volatile CloseReason closeReason;

        @OnOpen
        public void onOpen(Session session) {
        }

        @OnMessage
        public void onMessage(String message) {
            messages.add(message);
            anyMessage.countDown();
        }

        @OnClose
        public void onClose(CloseReason reason) {
            closeReason = reason;
            closed.countDown();
        }

        boolean awaitMessage(long seconds) throws InterruptedException {
            return anyMessage.await(seconds, TimeUnit.SECONDS);
        }
    }

    @Test
    void authenticatedClientReceivesHelloFrame() throws Exception {
        CollectingClient client = new CollectingClient();
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        try (Session session = container.connectToServer(client, uri("token=" + token("user")))) {
            assertThat(client.awaitMessage(5)).isTrue();
            assertThat(client.messages.getFirst()).contains("\"type\":\"hello\"");
            assertThat(client.messages.getFirst()).contains("\"server\":\"taskmesh-0.1.0\"");
        }
    }

    @Test
    void invalidTokenIsRejectedImmediately() throws Exception {
        CollectingClient client = new CollectingClient();
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        try (Session session = container.connectToServer(client, uri("token=not-a-token"))) {
            boolean wasClosed = client.closed.await(5, TimeUnit.SECONDS);
            assertThat(wasClosed).isTrue();
            assertThat(client.messages).isEmpty();
            assertThat(client.closeReason.getCloseCode().getCode()).isEqualTo(1008);
        }
    }

    @Test
    void submittedJobsAreBroadcastAsQueueEvents() throws Exception {
        CollectingClient client = new CollectingClient();
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        try (Session session = container.connectToServer(client, uri("token=" + token("user")))) {
            assertThat(client.awaitMessage(5)).isTrue();

            String projectId = JsonPath.<String>read(post("user", "/api/v1/projects",
                    Map.of("name", "WS Project " + UUID.randomUUID())).getBody(), "$.id");
            HttpStatusCode status = post("user", "/api/v1/jobs", Map.of(
                    "projectId", projectId, "type", "text_statistics",
                    "payload", Map.of("text", "ws event payload"))).getStatusCode();
            assertThat(status).isEqualTo(HttpStatus.CREATED);

            boolean eventArrived = awaitEvent(client, "job.enqueued", 20);
            assertThat(eventArrived)
                    .as("expected a job.enqueued queue event after submission, got: %s", client.messages)
                    .isTrue();
        }
    }

    private boolean awaitEvent(CollectingClient client, String expected, long seconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            if (client.messages.stream().anyMatch(m -> m.contains(expected))) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private URI uri(String query) {
        return URI.create("ws://localhost:" + port + "/ws/v1/events?" + query);
    }
}
