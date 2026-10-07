package com.taskmesh.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Pagination parameter handling: 0-based pages, size defaults to 25 and is clamped to
 * [1, 100] (SPEC §8). Negative pages are clamped to 0.
 */
public final class Pagination {

    public static final int DEFAULT_SIZE = 25;
    public static final int MAX_SIZE = 100;

    private Pagination() {
    }

    public static Pageable toPage(Integer page, Integer size, Sort sort) {
        int normalizedPage = page == null || page < 0 ? 0 : page;
        int normalizedSize = normalizeSize(size);
        return PageRequest.of(normalizedPage, normalizedSize, sort);
    }

    public static Pageable toPage(Integer page, Integer size) {
        return toPage(page, size, Sort.unsorted());
    }

    public static int normalizeSize(Integer size) {
        if (size == null) {
            return DEFAULT_SIZE;
        }
        return Math.min(Math.max(size, 1), MAX_SIZE);
    }

    /** Clamps a limit parameter (e.g. log lines) to [1, max]. */
    public static int normalizeLimit(Integer limit, int defaultSize, int maxSize) {
        if (limit == null) {
            return defaultSize;
        }
        return Math.min(Math.max(limit, 1), maxSize);
    }
}
