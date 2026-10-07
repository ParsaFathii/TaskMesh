package com.taskmesh.websocket;

import com.taskmesh.messaging.EventBroadcaster;
import com.taskmesh.security.AuthenticatedUser;
import com.taskmesh.security.JwtService;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Raw Jakarta WebSocket endpoint {@code /ws/v1/events} (SPEC §8).
 *
 * <p>Authentication happens via the {@code ?token=<JWT>} query parameter because browsers
 * cannot set headers on WebSocket handshakes. On successful authentication the server
 * greets the client with a {@code hello} frame and then pushes job/worker/queue events.
 * Instances are created by the WebSocket container, not by Spring; {@link #configure}
 * wires in the collaborators (see {@code WebSocketConfig} and
 * {@link ServerEndpointExporter}).</p>
 */
@ServerEndpoint("/ws/v1/events")
public class EventsWebSocket {

    private static final Logger log = LoggerFactory.getLogger(EventsWebSocket.class);

    private static final String HELLO_FRAME = """
            {"type":"hello","server":"taskmesh-0.1.0","timestamp":"%s"}""";

    private static volatile EventBroadcaster broadcaster;
    private static volatile JwtService jwtService;

    /** Called once during application wiring. */
    public static void configure(EventBroadcaster eventBroadcaster, JwtService service) {
        broadcaster = eventBroadcaster;
        jwtService = service;
    }

    /** An authenticated live client connection. */
    public record Client(Session session, AuthenticatedUser user) {
    }

    @OnOpen
    public void onOpen(Session session) {
        AuthenticatedUser user = authenticate(session);
        if (user == null) {
            close(session, new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY,
                    "invalid or expired token"));
            return;
        }
        broadcaster.register(new Client(session, user));
        try {
            synchronized (session) {
                session.getBasicRemote().sendText(
                        HELLO_FRAME.formatted(OffsetDateTime.now(ZoneOffset.UTC)));
            }
        } catch (IOException e) {
            log.debug("Failed to send hello frame: {}", e.getMessage());
        }
    }

    @OnClose
    public void onClose(Session session) {
        if (broadcaster != null) {
            broadcaster.unregister(session.getId());
        }
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.debug("WebSocket error on session {}: {}", session.getId(), error.getMessage());
        if (broadcaster != null) {
            broadcaster.unregister(session.getId());
        }
    }

    /** Server-to-client push protocol; incoming frames are ignored. */
    @OnMessage
    public void onMessage(Session session, String message) {
        // Intentionally empty: the protocol is server-push only (SPEC §8).
    }

    private AuthenticatedUser authenticate(Session session) {
        if (jwtService == null) {
            return null;
        }
        List<String> tokens = session.getRequestParameterMap().get("token");
        if (tokens == null || tokens.isEmpty()) {
            return null;
        }
        return jwtService.parse(tokens.get(0));
    }

    private void close(Session session, CloseReason reason) {
        try {
            session.close(reason);
        } catch (IOException e) {
            log.debug("Failed to close unauthorized session: {}", e.getMessage());
        }
    }
}
