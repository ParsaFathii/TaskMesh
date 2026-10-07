package com.taskmesh.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared setup for integration tests: boots the application against the local
 * PostgreSQL instance (database {@code taskmesh_test}, migrated by Flyway), caches one
 * login token per dev account and wipes volatile data after every test method.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    protected static final String ADMIN_PASSWORD = "TaskMesh!Admin";
    protected static final String OPERATOR_PASSWORD = "TaskMesh!Operator";
    protected static final String USER_PASSWORD = "TaskMesh!User";

    private static final Map<String, String> TOKEN_CACHE = new ConcurrentHashMap<>();

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper objectMapper;

    @LocalServerPort
    protected int port;

    @AfterEach
    void wipeTestData() {
        jdbc.update("DELETE FROM job_logs");
        jdbc.update("DELETE FROM job_results");
        jdbc.update("DELETE FROM job_attempts");
        jdbc.update("DELETE FROM jobs");
        jdbc.update("DELETE FROM projects");
        jdbc.update("DELETE FROM workers");
        jdbc.update("DELETE FROM audit_logs");
        jdbc.update("DELETE FROM users WHERE username NOT IN ('admin', 'operator', 'user')");
    }

    protected String token(String username) {
        // Only the long-lived seed accounts are cached: dynamically created test users
        // are deleted between tests, which would invalidate their cached tokens.
        if ("admin".equals(username) || "operator".equals(username) || "user".equals(username)) {
            return TOKEN_CACHE.computeIfAbsent(username, u -> login(u, passwordFor(u)));
        }
        return login(username, passwordFor(username));
    }

    protected String login(String username, String password) {
        ResponseEntity<String> response = rest.postForEntity("/api/v1/auth/login",
                jsonEntity(Map.of("username", username, "password", password)), String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("login failed for " + username + ": " + response.getStatusCode());
        }
        return com.jayway.jsonpath.JsonPath.read(response.getBody(), "$.token");
    }

    protected HttpEntity<String> jsonEntity(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(toJson(body), headers);
    }

    protected HttpEntity<String> authJsonEntity(String username, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(username));
        return new HttpEntity<>(toJson(body), headers);
    }

    protected HttpEntity<Void> authEntity(String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(username));
        return new HttpEntity<>(headers);
    }

    /** pgjdbc has no implicit varchar -> uuid cast: bind real UUIDs in fixture SQL. */
    protected static UUID uuid(String id) {
        return UUID.fromString(id);
    }

    protected ResponseEntity<String> get(String username, String path) {
        return rest.exchange(path, HttpMethod.GET, authEntity(username), String.class);
    }

    protected ResponseEntity<String> post(String username, String path, Object body) {
        return rest.exchange(path, HttpMethod.POST, authJsonEntity(username, body), String.class);
    }

    protected String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize test body", e);
        }
    }

    private static String passwordFor(String username) {
        return switch (username) {
            case "admin" -> ADMIN_PASSWORD;
            case "operator" -> OPERATOR_PASSWORD;
            case "mallory" -> "MalloryPass!9";
            case "alice" -> "AlicePass!42";
            default -> USER_PASSWORD;
        };
    }
}
