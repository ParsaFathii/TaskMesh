package com.taskmesh.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregated queue and cluster metrics for GET /api/v1/metrics. All numbers are computed
 * directly in SQL to avoid pulling rows into memory.
 */
@Service
public class MetricsService {

    private final JdbcTemplate jdbc;

    public MetricsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("jobsByStatus", jobsByStatus());
        metrics.put("queueDepthByPriority", queueDepthByPriority());
        metrics.put("workersByStatus", workersByStatus());
        metrics.put("succeededLast24h", succeededLast24h());
        metrics.put("completedPerHourLast24h", completedPerHourLast24h());
        return metrics;
    }

    private Map<String, Long> jobsByStatus() {
        Map<String, Long> counts = zeroedKeys(List.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED",
                "CANCELLED", "RETRYING", "TIMED_OUT"));
        jdbc.query("SELECT status::text, count(*) FROM jobs GROUP BY status",
                (RowCallbackHandler) rs -> counts.put(rs.getString(1), rs.getLong(2)));
        return counts;
    }

    private Map<String, Long> queueDepthByPriority() {
        Map<String, Long> counts = zeroedKeys(List.of("CRITICAL", "HIGH", "NORMAL", "LOW"));
        jdbc.query("""
                SELECT priority::text, count(*) FROM jobs
                WHERE status IN ('QUEUED', 'RETRYING') GROUP BY priority
                """, (RowCallbackHandler) rs -> counts.put(rs.getString(1), rs.getLong(2)));
        return counts;
    }

    private Map<String, Long> workersByStatus() {
        Map<String, Long> counts = zeroedKeys(List.of("STARTING", "IDLE", "BUSY", "DRAINING",
                "OFFLINE", "ERROR"));
        jdbc.query("SELECT status::text, count(*) FROM workers GROUP BY status",
                (RowCallbackHandler) rs -> counts.put(rs.getString(1), rs.getLong(2)));
        return counts;
    }

    private Map<String, Object> succeededLast24h() {
        return jdbc.queryForObject("""
                SELECT count(*),
                       coalesce(avg(extract(epoch from completed_at - started_at)), 0),
                       coalesce(max(extract(epoch from completed_at - started_at)), 0)
                FROM jobs
                WHERE status = 'SUCCEEDED' AND completed_at > now() - interval '24 hours'
                """, (rs, rowNum) -> {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("count", rs.getLong(1));
            block.put("avgDurationSeconds", round(rs.getDouble(2)));
            block.put("maxDurationSeconds", round(rs.getDouble(3)));
            return block;
        });
    }

    private double completedPerHourLast24h() {
        Long completed = jdbc.queryForObject("""
                SELECT count(*) FROM jobs
                WHERE completed_at > now() - interval '24 hours'
                """, Long.class);
        return round((completed == null ? 0 : completed) / 24.0);
    }

    private static Map<String, Long> zeroedKeys(List<String> keys) {
        Map<String, Long> counts = new LinkedHashMap<>();
        keys.forEach(key -> counts.put(key, 0L));
        return counts;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
