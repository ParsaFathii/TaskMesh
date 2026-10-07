package com.taskmesh.config;

import com.taskmesh.messaging.EventBroadcaster;
import com.taskmesh.security.JwtService;
import com.taskmesh.websocket.EventsWebSocket;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

/**
 * Wires the raw Jakarta WebSocket endpoint (container-instantiated, not a Spring bean)
 * with Spring-managed collaborators, and exports it on the embedded Tomcat.
 */
@Configuration
public class WebSocketConfig {

    WebSocketConfig(EventBroadcaster broadcaster, JwtService jwtService) {
        EventsWebSocket.configure(broadcaster, jwtService);
    }

    @Bean
    ServerEndpointExporter serverEndpointExporter() {
        ServerEndpointExporter exporter = new ServerEndpointExporter();
        // The exporter auto-registers @ServerEndpoint classes that are Spring beans;
        // EventsWebSocket is instantiated by the WebSocket container instead, so the
        // class must be registered explicitly.
        exporter.setAnnotatedEndpointClasses(EventsWebSocket.class);
        return exporter;
    }
}
