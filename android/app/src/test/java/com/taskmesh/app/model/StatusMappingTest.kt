package com.taskmesh.app.model

import com.taskmesh.app.data.JobPriority
import com.taskmesh.app.data.JobStatus
import com.taskmesh.app.data.WorkerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Presentation mapping: labels, tones and the SPEC §5 terminal set. */
class StatusMappingTest {

    @Test
    fun `job status labels are human readable`() {
        assertEquals("Queued", StatusMapping.label(JobStatus.QUEUED))
        assertEquals("Running", StatusMapping.label(JobStatus.RUNNING))
        assertEquals("Succeeded", StatusMapping.label(JobStatus.SUCCEEDED))
        assertEquals("Failed", StatusMapping.label(JobStatus.FAILED))
        assertEquals("Cancelled", StatusMapping.label(JobStatus.CANCELLED))
        assertEquals("Retrying", StatusMapping.label(JobStatus.RETRYING))
        assertEquals("Timed out", StatusMapping.label(JobStatus.TIMED_OUT))
    }

    @Test
    fun `terminal statuses per SPEC section 5`() {
        val terminal = setOf(JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED, JobStatus.TIMED_OUT)
        for (status in JobStatus.entries) {
            assertEquals("wrong terminal flag for $status", status in terminal, status.isTerminal())
            assertEquals(status in terminal, StatusMapping.isTerminal(status))
        }
        assertFalse(JobStatus.QUEUED.isTerminal())
        assertFalse(JobStatus.RUNNING.isTerminal())
        assertFalse(JobStatus.RETRYING.isTerminal())
    }

    @Test
    fun `tones follow the product palette semantics`() {
        // success -> emerald, failure -> rose, active -> amber
        assertEquals(Tone.EMERALD, StatusMapping.tone(JobStatus.SUCCEEDED))
        assertEquals(Tone.ROSE, StatusMapping.tone(JobStatus.FAILED))
        assertEquals(Tone.ROSE, StatusMapping.tone(JobStatus.TIMED_OUT))
        assertEquals(Tone.AMBER, StatusMapping.tone(JobStatus.RUNNING))
        assertEquals(Tone.AMBER, StatusMapping.tone(JobStatus.RETRYING))
        assertEquals(Tone.GRAY, StatusMapping.tone(JobStatus.CANCELLED))
        assertEquals(Tone.SLATE, StatusMapping.tone(JobStatus.QUEUED))
    }

    @Test
    fun `worker status labels and tones`() {
        assertEquals("Starting", StatusMapping.workerLabel(WorkerStatus.STARTING))
        assertEquals("Idle", StatusMapping.workerLabel(WorkerStatus.IDLE))
        assertEquals("Busy", StatusMapping.workerLabel(WorkerStatus.BUSY))
        assertEquals("Draining", StatusMapping.workerLabel(WorkerStatus.DRAINING))
        assertEquals("Offline", StatusMapping.workerLabel(WorkerStatus.OFFLINE))
        assertEquals("Error", StatusMapping.workerLabel(WorkerStatus.ERROR))

        assertEquals(Tone.EMERALD, StatusMapping.workerTone(WorkerStatus.IDLE))
        assertEquals(Tone.AMBER, StatusMapping.workerTone(WorkerStatus.BUSY))
        assertEquals(Tone.ROSE, StatusMapping.workerTone(WorkerStatus.OFFLINE))
        assertEquals(Tone.ROSE, StatusMapping.workerTone(WorkerStatus.ERROR))
    }

    @Test
    fun `log level tones`() {
        assertEquals(Tone.ROSE, StatusMapping.logTone("ERROR"))
        assertEquals(Tone.AMBER, StatusMapping.logTone("WARN"))
        assertEquals(Tone.EMERALD, StatusMapping.logTone("INFO"))
        assertEquals(Tone.GRAY, StatusMapping.logTone("DEBUG"))
        assertEquals(Tone.GRAY, StatusMapping.logTone("anything"))
    }

    @Test
    fun `priority labels are uppercase`() {
        assertEquals("LOW", StatusMapping.priorityLabel(JobPriority.LOW))
        assertEquals("NORMAL", StatusMapping.priorityLabel(JobPriority.NORMAL))
        assertEquals("HIGH", StatusMapping.priorityLabel(JobPriority.HIGH))
        assertEquals("CRITICAL", StatusMapping.priorityLabel(JobPriority.CRITICAL))
    }
}
