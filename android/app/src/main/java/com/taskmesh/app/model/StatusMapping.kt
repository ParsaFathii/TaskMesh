package com.taskmesh.app.model

import com.taskmesh.app.data.JobPriority
import com.taskmesh.app.data.JobStatus
import com.taskmesh.app.data.WorkerStatus

/**
 * Presentation mapping for job/worker statuses. Pure Kotlin (no Compose types)
 * so it is directly unit-testable; tones are resolved to colors in ui/theme.
 */
enum class Tone { AMBER, EMERALD, ROSE, GRAY, SLATE }

object StatusMapping {

    fun label(status: JobStatus): String = when (status) {
        JobStatus.QUEUED -> "Queued"
        JobStatus.RUNNING -> "Running"
        JobStatus.SUCCEEDED -> "Succeeded"
        JobStatus.FAILED -> "Failed"
        JobStatus.CANCELLED -> "Cancelled"
        JobStatus.RETRYING -> "Retrying"
        JobStatus.TIMED_OUT -> "Timed out"
    }

    fun tone(status: JobStatus): Tone = when (status) {
        JobStatus.QUEUED -> Tone.SLATE
        JobStatus.RUNNING -> Tone.AMBER
        JobStatus.SUCCEEDED -> Tone.EMERALD
        JobStatus.FAILED -> Tone.ROSE
        JobStatus.CANCELLED -> Tone.GRAY
        JobStatus.RETRYING -> Tone.AMBER
        JobStatus.TIMED_OUT -> Tone.ROSE
    }

    fun isTerminal(status: JobStatus): Boolean = when (status) {
        JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED, JobStatus.TIMED_OUT -> true
        else -> false
    }

    fun workerLabel(status: WorkerStatus): String = when (status) {
        WorkerStatus.STARTING -> "Starting"
        WorkerStatus.IDLE -> "Idle"
        WorkerStatus.BUSY -> "Busy"
        WorkerStatus.DRAINING -> "Draining"
        WorkerStatus.OFFLINE -> "Offline"
        WorkerStatus.ERROR -> "Error"
    }

    fun workerTone(status: WorkerStatus): Tone = when (status) {
        WorkerStatus.STARTING -> Tone.SLATE
        WorkerStatus.IDLE -> Tone.EMERALD
        WorkerStatus.BUSY -> Tone.AMBER
        WorkerStatus.DRAINING -> Tone.GRAY
        WorkerStatus.OFFLINE -> Tone.ROSE
        WorkerStatus.ERROR -> Tone.ROSE
    }

    fun logTone(level: String): Tone = when (level.uppercase()) {
        "ERROR" -> Tone.ROSE
        "WARN" -> Tone.AMBER
        "INFO" -> Tone.EMERALD
        else -> Tone.GRAY
    }

    fun priorityLabel(priority: JobPriority): String = when (priority) {
        JobPriority.LOW -> "LOW"
        JobPriority.NORMAL -> "NORMAL"
        JobPriority.HIGH -> "HIGH"
        JobPriority.CRITICAL -> "CRITICAL"
    }
}

/** Terminal statuses per SPEC §5 (no further legal transitions). */
fun JobStatus.isTerminal(): Boolean = StatusMapping.isTerminal(this)
