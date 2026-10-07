package com.taskmesh.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pagination math per SPEC §8: 0-based pages, default size 25, size clamped to [1, 100].
 */
class PaginationTest {

    @Test
    void defaultsApplyForMissingParameters() {
        assertThat(Pagination.normalizeSize(null)).isEqualTo(25);
        var page = Pagination.toPage(null, null);
        assertThat(page.getPageNumber()).isZero();
        assertThat(page.getPageSize()).isEqualTo(25);
    }

    @Test
    void sizeIsClampedToLowerAndUpperBounds() {
        assertThat(Pagination.normalizeSize(0)).isEqualTo(1);
        assertThat(Pagination.normalizeSize(-5)).isEqualTo(1);
        assertThat(Pagination.normalizeSize(1)).isEqualTo(1);
        assertThat(Pagination.normalizeSize(50)).isEqualTo(50);
        assertThat(Pagination.normalizeSize(100)).isEqualTo(100);
        assertThat(Pagination.normalizeSize(101)).isEqualTo(100);
        assertThat(Pagination.normalizeSize(10_000)).isEqualTo(100);
    }

    @Test
    void negativePagesAreClampedToZero() {
        assertThat(Pagination.toPage(-3, 25).getPageNumber()).isZero();
        assertThat(Pagination.toPage(7, 25).getPageNumber()).isEqualTo(7);
    }

    @Test
    void limitsAreClamped() {
        assertThat(Pagination.normalizeLimit(null, 100, 1000)).isEqualTo(100);
        assertThat(Pagination.normalizeLimit(0, 100, 1000)).isEqualTo(1);
        assertThat(Pagination.normalizeLimit(5_000, 100, 1000)).isEqualTo(1000);
        assertThat(Pagination.normalizeLimit(42, 100, 1000)).isEqualTo(42);
    }
}
