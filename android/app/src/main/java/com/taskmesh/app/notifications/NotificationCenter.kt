package com.taskmesh.app.notifications

import com.taskmesh.app.data.JobDto
import com.taskmesh.app.data.JobStatus
import com.taskmesh.app.data.WorkerDto
import com.taskmesh.app.data.WorkerStatus
import com.taskmesh.app.model.isTerminal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EventKind { JOB_COMPLETED, JOB_FAILED, JOB_TIMED_OUT, JOB_CANCELLED, WORKER_OFFLINE }

data class AppEvent(
    val id: Long,
    val kind: EventKind,
    val refId: String,
    val title: String,
    val message: String,
    val timestamp: Long,
    /** Deduplication key — one event per (job, terminal status) / worker offline. */
    val dedupKey: String,
    val read: Boolean = false,
)

val EventKind.label: String
    get() = when (this) {
        EventKind.JOB_COMPLETED -> "Completed"
        EventKind.JOB_FAILED -> "Failed"
        EventKind.JOB_TIMED_OUT -> "Timed out"
        EventKind.JOB_CANCELLED -> "Cancelled"
        EventKind.WORKER_OFFLINE -> "Worker offline"
    }

/**
 * In-memory application-level notification center (pure Kotlin, unit-testable).
 *
 * The caller feeds it observed job/worker snapshots (typically from the 30 s
 * foreground poll in AppViewModel). The center emits an [AppEvent] only for an
 * observed *transition* into a terminal job status (or worker OFFLINE), and
 * de-duplicates by [AppEvent.dedupKey] so re-observing the same snapshot never
 * duplicates events.
 */
class NotificationCenter(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val lock = Any()

    private val eventList = mutableListOf<AppEvent>()
    private val seenKeys = HashSet<String>()
    private val lastJobStatus = HashMap<String, JobStatus>()
    private val lastWorkerStatus = HashMap<String, WorkerStatus>()
    private var nextId = 1L
    private var listener: ((AppEvent) -> Unit)? = null

    private val eventsInternal = MutableStateFlow<List<AppEvent>>(emptyList())
    val events: StateFlow<List<AppEvent>> = eventsInternal.asStateFlow()

    private val unreadInternal = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = unreadInternal.asStateFlow()

    fun setListener(onEvent: (AppEvent) -> Unit) {
        synchronized(lock) { listener = onEvent }
    }

    fun observeJobs(jobs: List<JobDto>) {
        val fresh = synchronized(lock) {
            val emitted = mutableListOf<AppEvent>()
            for (job in jobs) {
                val previous = lastJobStatus.put(job.id, job.status)
                val transitionedToTerminal = previous != null &&
                    previous != job.status &&
                    job.status.isTerminal()
                if (transitionedToTerminal) {
                    val event = buildJobEvent(job)
                    if (seenKeys.add(event.dedupKey)) {
                        addEventLocked(event)
                        emitted.add(event)
                    }
                }
            }
            publishLocked()
            emitted
        }
        dispatch(fresh)
    }

    fun observeWorkers(workers: List<WorkerDto>) {
        val fresh = synchronized(lock) {
            val emitted = mutableListOf<AppEvent>()
            for (worker in workers) {
                val previous = lastWorkerStatus.put(worker.id, worker.status)
                val wentOffline = previous != null &&
                    previous != worker.status &&
                    worker.status == WorkerStatus.OFFLINE
                if (wentOffline) {
                    val event = buildWorkerEvent(worker)
                    if (seenKeys.add(event.dedupKey)) {
                        addEventLocked(event)
                        emitted.add(event)
                    }
                }
            }
            publishLocked()
            emitted
        }
        dispatch(fresh)
    }

    fun markAllRead() {
        synchronized(lock) {
            eventList.replaceAll { event -> event.copy(read = true) }
            publishLocked()
        }
    }

    fun clear() {
        synchronized(lock) {
            eventList.clear()
            publishLocked()
        }
    }

    private fun addEventLocked(event: AppEvent) {
        eventList.add(0, event)
        if (eventList.size > MAX_EVENTS) {
            eventList.subList(MAX_EVENTS, eventList.size).clear()
        }
    }

    private fun dispatch(events: List<AppEvent>) {
        val callback = synchronized(lock) { listener }
        for (event in events) {
            callback?.invoke(event)
        }
    }

    private fun publishLocked() {
        eventsInternal.value = eventList.toList()
        unreadInternal.value = eventList.count { event -> !event.read }
    }

    private fun buildJobEvent(job: JobDto): AppEvent {
        val kind = when (job.status) {
            JobStatus.SUCCEEDED -> EventKind.JOB_COMPLETED
            JobStatus.FAILED -> EventKind.JOB_FAILED
            JobStatus.TIMED_OUT -> EventKind.JOB_TIMED_OUT
            JobStatus.CANCELLED -> EventKind.JOB_CANCELLED
            else -> EventKind.JOB_FAILED
        }
        val statusText = job.status.name.lowercase().replace('_', ' ')
        val details = buildString {
            append(job.id.take(8))
            if (job.retryCount > 0) {
                append(" · ${job.retryCount} retries")
            }
            job.lastError?.take(80)?.let { error -> append(" · $error") }
        }
        return AppEvent(
            id = nextId++,
            kind = kind,
            refId = job.id,
            title = "Job ${job.type} $statusText",
            message = details,
            timestamp = clock(),
            dedupKey = "job:${job.id}:${job.status.name}",
        )
    }

    private fun buildWorkerEvent(worker: WorkerDto): AppEvent {
        return AppEvent(
            id = nextId++,
            kind = EventKind.WORKER_OFFLINE,
            refId = worker.id,
            title = "Worker ${worker.name} went offline",
            message = "${worker.hostname} · ${worker.id.take(8)}",
            timestamp = clock(),
            dedupKey = "worker:${worker.id}:OFFLINE",
        )
    }

    companion object {
        /** Cap of the in-memory event list (newest first). */
        const val MAX_EVENTS = 100
    }
}
