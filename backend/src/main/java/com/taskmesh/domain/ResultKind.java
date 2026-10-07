package com.taskmesh.domain;

/**
 * Result storage strategy (mirrors the PostgreSQL {@code result_kind} enum):
 * small results inline in JSONB, large ones as files under the storage root.
 */
public enum ResultKind {
    inline,
    file
}
