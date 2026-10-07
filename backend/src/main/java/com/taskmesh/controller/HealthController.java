package com.taskmesh.controller;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Liveness/readiness endpoint (public, SPEC §8): checks the database on every call.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private static final String VERSION = "0.1.0";

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        String db = "UP";
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
        } catch (DataAccessException e) {
            db = "DOWN";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP".equals(db) ? "UP" : "DOWN");
        body.put("db", db);
        body.put("version", VERSION);
        return "UP".equals(db)
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
