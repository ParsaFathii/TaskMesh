package com.taskmesh.security;

import com.taskmesh.config.TaskMeshProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sliding-window limiter for login attempts: at most {@code limit} attempts
 * per username per rolling {@code windowSeconds}. Attempt = any login request for that
 * username, successful or not. Sufficient for a single-instance deployment; a clustered
 * deployment would move this state into Redis.
 */
@Component
public class LoginRateLimiter {

    private static final long WINDOW_SECONDS = 60;

    private final int limit;
    private final ConcurrentHashMap<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();

    public LoginRateLimiter(TaskMeshProperties properties) {
        this.limit = Math.max(1, properties.loginRateLimitPerMinute());
    }

    /**
     * Records an attempt for the given username.
     *
     * @return true if the attempt is allowed, false when the limit is exceeded
     */
    public boolean tryAcquire(String username) {
        if (username == null || username.isBlank()) {
            return true;
        }
        Deque<Instant> window = attempts.computeIfAbsent(username, k -> new ArrayDeque<>());
        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds(WINDOW_SECONDS);
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
                window.removeFirst();
            }
            if (window.size() >= limit) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    /** Test hook: forget all attempts recorded for a username. */
    public void reset(String username) {
        attempts.remove(username);
    }
}
