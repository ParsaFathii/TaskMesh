package com.taskmesh.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/**
 * Request correlation and access logging. Generates (or propagates) an {@code X-Request-Id},
 * exposes it in the MDC so every JSON log line of the request carries it, echoes it in the
 * response and logs one access line per request.
 */
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = sanitize(request.getHeader(REQUEST_ID_HEADER));
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        long start = System.nanoTime();
        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            if ("/api/v1/health".equals(request.getRequestURI())) {
                log.debug("http {} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(),
                        response.getStatus(), durationMs);
            } else {
                log.info("http {} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(),
                        response.getStatus(), durationMs);
            }
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    private static String sanitize(String header) {
        if (header == null || header.isBlank() || header.length() > 64) {
            return null;
        }
        boolean safe = header.codePoints().allMatch(codePoint ->
                codePoint >= '0' && codePoint <= '9'
                        || codePoint >= 'a' && codePoint <= 'z'
                        || codePoint >= 'A' && codePoint <= 'Z'
                        || codePoint == '-' || codePoint == '.' || codePoint == '_');
        return safe ? header : null;
    }
}
