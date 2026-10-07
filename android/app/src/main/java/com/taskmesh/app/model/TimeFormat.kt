package com.taskmesh.app.model

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Timestamp helpers over ISO-8601 strings (as carried by the DTOs).
 * Falls back through Instant -> OffsetDateTime -> LocalDateTime(UTC) parsing
 * so all common Spring Boot serializations are accepted.
 */
object TimeFormat {

    fun toInstant(iso: String?): Instant? {
        if (iso.isNullOrBlank()) {
            return null
        }
        return try {
            Instant.parse(iso)
        } catch (e: DateTimeParseException) {
            try {
                OffsetDateTime.parse(iso).toInstant()
            } catch (e2: DateTimeParseException) {
                try {
                    LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC)
                } catch (e3: DateTimeParseException) {
                    null
                }
            }
        }
    }

    fun relative(iso: String?, nowMs: Long = System.currentTimeMillis()): String {
        val instant = toInstant(iso)
            ?: return (iso ?: "—").ifBlank { "—" }
        return relativeTo(instant.toEpochMilli(), nowMs)
    }

    fun relativeTo(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val diffSeconds = (nowMs - timestampMs) / 1000
        // One bucket per unit: never render a zero-valued unit ("0m ago").
        return when {
            diffSeconds < 0L -> "just now"
            diffSeconds < 60L -> "${diffSeconds}s ago"
            diffSeconds < 3_600L -> "${diffSeconds / 60}m ago"
            diffSeconds < 86_400L -> "${diffSeconds / 3_600}h ago"
            else -> "${diffSeconds / 86_400}d ago"
        }
    }

    fun display(iso: String?): String {
        val instant = toInstant(iso) ?: return iso ?: "—"
        return DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC)
            .format(instant)
    }
}
