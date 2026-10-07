package com.taskmesh.security;

import com.taskmesh.config.TaskMeshProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sliding-window login rate limiting: 10 attempts per username per minute.
 */
class LoginRateLimiterTest {

    private LoginRateLimiter limiter(int limit) {
        return new LoginRateLimiter(new TaskMeshProperties("secret", "./data",
                "http://localhost:5173", limit, true, true, "taskmesh_events"));
    }

    @Test
    void allowsUpToTheLimitThenBlocks() {
        LoginRateLimiter limiter = limiter(10);
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("ada")).as("attempt " + (i + 1)).isTrue();
        }
        assertThat(limiter.tryAcquire("ada")).isFalse();
        assertThat(limiter.tryAcquire("ada")).isFalse();
    }

    @Test
    void windowsAreIndependentPerUsername() {
        LoginRateLimiter limiter = limiter(2);
        assertThat(limiter.tryAcquire("ada")).isTrue();
        assertThat(limiter.tryAcquire("ada")).isTrue();
        assertThat(limiter.tryAcquire("ada")).isFalse();
        assertThat(limiter.tryAcquire("grace")).isTrue();
    }

    @Test
    void blankUsernamesAreNotLimited() {
        LoginRateLimiter limiter = limiter(1);
        assertThat(limiter.tryAcquire("")).isTrue();
        assertThat(limiter.tryAcquire(null)).isTrue();
    }

    @Test
    void resetClearsRecordedAttempts() {
        LoginRateLimiter limiter = limiter(1);
        assertThat(limiter.tryAcquire("ada")).isTrue();
        assertThat(limiter.tryAcquire("ada")).isFalse();
        limiter.reset("ada");
        assertThat(limiter.tryAcquire("ada")).isTrue();
    }
}
