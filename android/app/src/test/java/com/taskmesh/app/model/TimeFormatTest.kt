package com.taskmesh.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** ISO-8601 parsing and relative/display rendering. */
class TimeFormatTest {

    @Test
    fun `parses instant with zulu suffix`() {
        val instant = TimeFormat.toInstant("2026-01-06T18:00:00Z")
        assertNotNull(instant)
        assertEquals(Instant.parse("2026-01-06T18:00:00Z"), instant)
    }

    @Test
    fun `parses utc offset form emitted by spring`() {
        val instant = TimeFormat.toInstant("2026-01-06T18:00:00.123456+00:00")
        assertNotNull(instant)
        assertEquals(123456_000L, instant?.nano?.toLong())
    }

    @Test
    fun `parses positive offset form`() {
        val instant = TimeFormat.toInstant("2026-01-06T18:00:00+02:00")
        assertNotNull(instant)
        assertEquals(Instant.parse("2026-01-06T16:00:00Z"), instant)
    }

    @Test
    fun `parses naive local datetime as utc`() {
        val instant = TimeFormat.toInstant("2026-01-06T18:00:00")
        assertEquals(Instant.parse("2026-01-06T18:00:00Z"), instant)
    }

    @Test
    fun `rejects garbage and blanks`() {
        assertNull(TimeFormat.toInstant("not a timestamp"))
        assertNull(TimeFormat.toInstant(""))
        assertNull(TimeFormat.toInstant(null))
        assertNull(TimeFormat.toInstant("   "))
    }

    @Test
    fun `relative renders age buckets`() {
        val now = 1_000_000_000_000L
        assertEquals("30s ago", TimeFormat.relativeTo(now - 30_000, now))
        assertEquals("59s ago", TimeFormat.relativeTo(now - 59_000, now))
        assertEquals("10m ago", TimeFormat.relativeTo(now - 600_000, now))
        assertEquals("3h ago", TimeFormat.relativeTo(now - 3 * 3_600_000, now))
        assertEquals("2d ago", TimeFormat.relativeTo(now - 2 * 86_400_000, now))
        // Future timestamps (clock skew) never render negative ages.
        assertEquals("just now", TimeFormat.relativeTo(now + 5_000, now))
    }

    @Test
    fun `relative of ISO string falls back to raw text`() {
        assertEquals("—", TimeFormat.relative(null, nowMs = 0L))
        assertEquals("—", TimeFormat.relative("", nowMs = 0L))
        assertEquals("garbage", TimeFormat.relative("garbage", nowMs = 0L))
    }

    @Test
    fun `display renders UTC`() {
        assertEquals(
            "2026-01-06 18:00 UTC",
            TimeFormat.display("2026-01-06T18:00:00Z"),
        )
        assertEquals("—", TimeFormat.display(null))
    }
}
