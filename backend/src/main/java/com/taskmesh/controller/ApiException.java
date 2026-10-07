package com.taskmesh.controller;

/**
 * Base class for API error responses mapped by {@link ApiExceptionHandler}.
 */
public abstract class ApiException extends RuntimeException {

    private final String code;

    protected ApiException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Stable error code rendered as {@code {"error":{"code":...}}}. */
    public String getCode() {
        return code;
    }

    /** HTTP status for this error. */
    public abstract int status();

    public static class NotFound extends ApiException {
        public NotFound(String message) {
            super("NOT_FOUND", message);
        }

        @Override
        public int status() {
            return 404;
        }
    }

    public static class Validation extends ApiException {
        public Validation(String message) {
            super("VALIDATION", message);
        }

        @Override
        public int status() {
            return 400;
        }
    }

    public static class Unauthorized extends ApiException {
        public Unauthorized(String message) {
            super("UNAUTHORIZED", message);
        }

        @Override
        public int status() {
            return 401;
        }
    }

    public static class Forbidden extends ApiException {
        public Forbidden(String message) {
            super("FORBIDDEN", message);
        }

        @Override
        public int status() {
            return 403;
        }
    }

    public static class Conflict extends ApiException {
        public Conflict(String message) {
            super("CONFLICT", message);
        }

        @Override
        public int status() {
            return 409;
        }
    }

    public static class RateLimited extends ApiException {
        public RateLimited(String message) {
            super("RATE_LIMITED", message);
        }

        @Override
        public int status() {
            return 429;
        }
    }
}
