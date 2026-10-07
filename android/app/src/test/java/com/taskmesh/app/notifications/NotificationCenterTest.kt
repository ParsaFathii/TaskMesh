package com.taskmesh.app.notifications

import com.taskmesh.app.data.JobDto
import com.taskmesh.app.data.JobStatus
import com.taskmesh.app.data.WorkerDto
import com.taskmesh.app.data.WorkerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transition detection + dedup for the local NotificationCenter (SPEC §5
 * terminal statuses; prompt §33 event kinds).
 */
class NotificationCenterTest {

    private var clockMs = 1_000_000L
    private val center = NotificationCenter(clock = { clockMs })

    private fun job(id: String, status: JobStatus, type: String = "text_statistics") = JobDto(
        id = id,
        projectId = "project-1",
        ownerId = "owner-1",
        type = type,
        status = status,
    )

    private fun worker(id: String, status: WorkerStatus) = WorkerDto(
        id = id,
        name = "worker-$id",
        hostname = "host",
        version = "0.1.0",
        status = status,
    )

    @Test
    fun `first observation establishes baseline without events`() {
        center.observeJobs(listOf(job("j1", JobStatus.RUNNING)))
        assertTrue(center.events.value.isEmpty())
        assertEquals(0, center.unreadCount.value)
    }

    @Test
    fun `terminal transition emits exactly one event`() {
        center.observeJobs(listOf(job("j1", JobStatus.RUNNING)))
        clockMs += 30_000
        center.observeJobs(listOf(job("j1", JobStatus.SUCCEEDED)))

        val events = center.events.value
        assertEquals(1, events.size)
        val event = events.first()
        assertEquals(EventKind.JOB_COMPLETED, event.kind)
        assertEquals("j1", event.refId)
        assertEquals("job:j1:SUCCEEDED", event.dedupKey)
        assertEquals(1, center.unreadCount.value)
    }

    @Test
    fun `re-observing the same terminal status deduplicates`() {
        center.observeJobs(listOf(job("j1", JobStatus.RUNNING)))
        center.observeJobs(listOf(job("j1", JobStatus.SUCCEEDED)))
        clockMs += 30_000
        center.observeJobs(listOf(job("j1", JobStatus.SUCCEEDED)))
        clockMs += 30_000
        center.observeJobs(listOf(job("j1", JobStatus.SUCCEEDED)))

        assertEquals(1, center.events.value.size)
    }

    @Test
    fun `non-terminal transitions do not emit`() {
        center.observeJobs(listOf(job("j1", JobStatus.QUEUED)))
        center.observeJobs(listOf(job("j1", JobStatus.RUNNING)))
        center.observeJobs(listOf(job("j1", JobStatus.RETRYING)))
        center.observeJobs(listOf(job("j1", JobStatus.RUNNING)))

        assertTrue(center.events.value.isEmpty())
    }

    @Test
    fun `failure timeout and cancellation map to their event kinds`() {
        center.observeJobs(
            listOf(
                job("j1", JobStatus.RUNNING),
                job("j2", JobStatus.RUNNING),
                job("j3", JobStatus.RETRYING),
            ),
        )
        center.observeJobs(
            listOf(
                job("j1", JobStatus.FAILED),
                job("j2", JobStatus.TIMED_OUT),
                job("j3", JobStatus.CANCELLED),
            ),
        )

        val events = center.events.value
        assertEquals(3, events.size)
        assertEquals(
            setOf(EventKind.JOB_FAILED, EventKind.JOB_TIMED_OUT, EventKind.JOB_CANCELLED),
            events.map { it.kind }.toSet(),
        )
        assertEquals(3, center.unreadCount.value)
    }

    @Test
    fun `worker going offline emits once and deduplicates`() {
        center.observeWorkers(listOf(worker("w1", WorkerStatus.IDLE)))
        center.observeWorkers(listOf(worker("w1", WorkerStatus.BUSY)))
        center.observeWorkers(listOf(worker("w1", WorkerStatus.OFFLINE)))
        center.observeWorkers(listOf(worker("w1", WorkerStatus.OFFLINE)))

        val events = center.events.value
        assertEquals(1, events.size)
        assertEquals(EventKind.WORKER_OFFLINE, events.first().kind)
        assertEquals("worker:w1:OFFLINE", events.first().dedupKey)
    }

    @Test
    fun `worker that recovers and goes offline again is not re-notified`() {
        center.observeWorkers(listOf(worker("w1", WorkerStatus.IDLE)))
        center.observeWorkers(listOf(worker("w1", WorkerStatus.OFFLINE)))
        center.observeWorkers(listOf(worker("w1", WorkerStatus.IDLE)))
        center.observeWorkers(listOf(worker("w1", WorkerStatus.OFFLINE)))

        // One notification per (worker, OFFLINE) — the recovery blip must not
        // produce a duplicate.
        assertEquals(1, center.events.value.size)
    }

    @Test
    fun `pre-existing offline baseline never notifies`() {
        // First observation (unknown state) only records the baseline.
        center.observeWorkers(listOf(worker("w1", WorkerStatus.OFFLINE)))
        center.observeWorkers(listOf(worker("w1", WorkerStatus.OFFLINE)))

        assertTrue(center.events.value.isEmpty())
    }

    @Test
    fun `listener receives fresh events in order`() {
        val received = mutableListOf<AppEvent>()
        center.setListener { received.add(it) }

        center.observeJobs(listOf(job("j1", JobStatus.RUNNING)))
        center.observeJobs(listOf(job("j2", JobStatus.RUNNING)))
        center.observeJobs(listOf(job("j1", JobStatus.SUCCEEDED), job("j2", JobStatus.FAILED)))

        assertEquals(2, received.size)
        assertEquals(EventKind.JOB_COMPLETED, received[0].kind)
        assertEquals(EventKind.JOB_FAILED, received[1].kind)
    }

    @Test
    fun `mark all read resets unread but keeps the feed`() {
        center.observeJobs(listOf(job("j1", JobStatus.RUNNING)))
        center.observeJobs(listOf(job("j1", JobStatus.SUCCEEDED)))
        assertEquals(1, center.unreadCount.value)

        center.markAllRead()

        assertEquals(0, center.unreadCount.value)
        assertEquals(1, center.events.value.size)
        assertTrue(center.events.value.first().read)
    }

    @Test
    fun `clear empties the feed`() {
        center.observeJobs(listOf(job("j1", JobStatus.RUNNING)))
        center.observeJobs(listOf(job("j1", JobStatus.SUCCEEDED)))
        center.clear()

        assertTrue(center.events.value.isEmpty())
        assertEquals(0, center.unreadCount.value)
    }

    @Test
    fun `event list is capped at 100 newest-first entries`() {
        val running = (1..150).map { job("job-$it", JobStatus.RUNNING) }
        center.observeJobs(running)
        val finished = (1..150).map { job("job-$it", JobStatus.SUCCEEDED) }
        center.observeJobs(finished)

        val events = center.events.value
        assertEquals(NotificationCenter.MAX_EVENTS, events.size)
        // Newest first: the last-completed job (150) leads the list.
        assertEquals("job-150", events.first().refId)
    }

    @Test
    fun `job event message carries short id and retry count`() {
        val running = JobDto(
            id = "12345678-1234-1234-1234-123456789012",
            projectId = "p",
            ownerId = "o",
            type = "csv_analysis",
            status = JobStatus.RUNNING,
            retryCount = 2,
        )
        val failed = running.copy(status = JobStatus.FAILED, lastError = "disk full")
        center.observeJobs(listOf(running))
        center.observeJobs(listOf(failed))

        val event = center.events.value.single()
        assertTrue(event.title.contains("csv_analysis"))
        assertTrue(event.message.contains("12345678"))
        assertTrue(event.message.contains("2"))
        assertTrue(event.message.contains("disk full"))
    }
}
