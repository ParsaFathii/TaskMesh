package com.taskmesh.controller.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Pagination envelope per SPEC §8: {@code {items, page, size, total}} with 0-based
 * pages (default size 25, max 100).
 */
public record PageResponse<T>(List<T> items, int page, int size, long total) {

    public static <T, E> PageResponse<T> of(Page<E> page, java.util.function.Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements());
    }

    public static <T> PageResponse<T> ofList(List<T> items, int page, int size, long total) {
        return new PageResponse<>(items, page, size, total);
    }
}
