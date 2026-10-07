package com.taskmesh.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Wire DTOs matching the TaskMesh REST API (SPEC §8) EXACTLY.
 *
 * Conventions:
 *  - camelCase field names (no @SerialName needed except where the Kotlin
 *    identifier must differ from the wire name);
 *  - ISO-8601 timestamps are plain strings (parsed for display in `model/TimeFormat`);
 *  - enum names are uppercase on the wire (kotlinx default = enum name);
 *  - optional/nullable backend fields (`progress`, `workerId`, `lastError`,
 *    `queuedAt`, ...) declare Kotlin defaults so missing keys decode cleanly and
 *    `null` decodes as `null`;
 *  - `ignoreUnknownKeys` is enabled in [JsonConfig] so future backend fields
 *    cannot break older clients.
 */

// ---------------------------------------------------------------------------
// Enums (SPEC §4: job_status / job_priority / worker_status)
// ---------------------------------------------------------------------------

@Serializable
enum class JobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    RETRYING,
    TIMED_OUT,
}

@Serializable
enum class JobPriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL,
}

@Serializable
enum class WorkerStatus {
    STARTING,
    IDLE,
    BUSY,
    DRAINING,
    OFFLINE,
    ERROR,
}

// ---------------------------------------------------------------------------
// Auth (POST /api/v1/auth/login, GET /api/v1/me)
// ---------------------------------------------------------------------------

@Serializable
data class LoginRequestDto(
    val username: String,
    val password: String,
)

/** `user` brief embedded in the login response: {id, username, role}. */
@Serializable
data class UserBriefDto(
    val id: String,
    val username: String,
    val role: String,
) {
    fun toUser(): UserDto = UserDto(id = id, username = username, role = role)
}

@Serializable
data class LoginResponseDto(
    val token: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long = 0L,
    val user: UserBriefDto,
)

/**
 * Current user. The login response only carries {id, username, role};
 * GET /api/v1/me additionally returns email/createdAt/updatedAt, so those
 * are optional here.
 */
@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val role: String,
    val email: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

/** ROLE gate per SPEC §11: workers/logs/metrics endpoints need OPERATOR or ADMIN. */
val UserDto?.isOperator: Boolean
    get() = this != null && (this.role == "ADMIN" || this.role == "OPERATOR")

// ---------------------------------------------------------------------------
// Pagination envelope (SPEC §8): {items, page, size, total}
// ---------------------------------------------------------------------------

@Serializable
data class PageDto<T>(
    val items: List<T> = emptyList(),
    val page: Int = 0,
    val size: Int = 25,
    val total: Long = 0L,
)

// ---------------------------------------------------------------------------
// Error envelope (SPEC §8): {"error":{"code":"...","message":"..."}}
// ---------------------------------------------------------------------------

@Serializable
data class ErrorBodyDto(
    val code: String? = null,
    val message: String? = null,
)

@Serializable
data class ErrorEnvelopeDto(
    val error: ErrorBodyDto? = null,
)

// ---------------------------------------------------------------------------
// Projects (GET/POST /api/v1/projects)
// ---------------------------------------------------------------------------

@Serializable
data class ProjectDto(
    val id: String,
    val name: String,
    val description: String = "",
    val ownerId: String,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class ProjectCreateRequest(
    val name: String,
    val description: String = "",
)

// ---------------------------------------------------------------------------
// Jobs (GET/POST /api/v1/jobs, /api/v1/jobs/{id}, /cancel, /retry)
// ---------------------------------------------------------------------------

/** Worker summary embedded in the job detail response: {id, name, hostname, status}. */
@Serializable
data class WorkerBriefDto(
    val id: String,
    val name: String,
    val hostname: String,
    val status: WorkerStatus,
)

/**
 * Job representation used for both list and detail responses. The list
 * response carries no `worker` key (only `workerId`); the detail response is
 * `{job: {...}, worker: {...}}` and [SessionRepository] merges the worker
 * summary into this DTO after decoding.
 */
@Serializable
data class JobDto(
    val id: String,
    val projectId: String,
    val ownerId: String,
    val type: String,
    val priority: JobPriority = JobPriority.NORMAL,
    val payload: JsonObject = JsonObject(emptyMap()),
    val status: JobStatus = JobStatus.QUEUED,
    val idempotencyKey: String? = null,
    val createdAt: String? = null,
    val queuedAt: String? = null,
    val startedAt: String? = null,
    val completedAt: String? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val timeoutSeconds: Int = 120,
    val workerId: String? = null,
    /** 0..100 or null while the worker has not reported progress. */
    val progress: Int? = null,
    val cancelRequested: Boolean = false,
    val lastError: String? = null,
    /** Only present when decoded from the job detail endpoint. */
    val worker: WorkerBriefDto? = null,
)

/** GET /api/v1/jobs/{id} response: {job, worker}. */
@Serializable
data class JobDetailDto(
    val job: JobDto,
    val worker: WorkerBriefDto? = null,
)

@Serializable
data class JobCreateRequest(
    val projectId: String,
    val type: String,
    val priority: JobPriority = JobPriority.NORMAL,
    val payload: JsonElement,
    val maxRetries: Int? = null,
    val timeoutSeconds: Int? = null,
    val idempotencyKey: String? = null,
)

// ---------------------------------------------------------------------------
// Job logs & attempts (GET /api/v1/jobs/{id}/logs, /attempts — plain arrays)
// ---------------------------------------------------------------------------

@Serializable
data class JobLogDto(
    val id: Long? = null,
    val jobId: String? = null,
    val workerId: String? = null,
    val level: String = "INFO",
    val message: String,
    val metadata: JsonObject? = null,
    val createdAt: String? = null,
)

@Serializable
data class JobAttemptDto(
    val id: Long? = null,
    val jobId: String? = null,
    val attemptNumber: Int = 0,
    val workerId: String? = null,
    val startedAt: String? = null,
    val finishedAt: String? = null,
    /** SUCCEEDED | FAILED | TIMED_OUT | CANCELLED | ABANDONED | null (running). */
    val outcome: String? = null,
    val error: String? = null,
)

// ---------------------------------------------------------------------------
// Workers (GET /api/v1/workers — paginated, includes computed `stale`)
// ---------------------------------------------------------------------------

@Serializable
data class WorkerDto(
    val id: String,
    val name: String,
    val hostname: String,
    val version: String,
    val status: WorkerStatus = WorkerStatus.STARTING,
    val capabilities: List<String> = emptyList(),
    val startedAt: String? = null,
    val lastHeartbeat: String? = null,
    val heartbeatIntervalS: Int = 0,
    val currentJobId: String? = null,
    val controlPort: Int? = null,
    val stale: Boolean = false,
)

// ---------------------------------------------------------------------------
// Job type catalog (GET /api/v1/job-types — plain array)
// ---------------------------------------------------------------------------

/** One catalog entry: {type, description, payloadSchema, example} (SPEC §9). */
@Serializable
data class JobTypeDto(
    /** Wire name is `type`; the app calls it `typeName` to avoid clashing with Kotlin's `type`. */
    @SerialName("type") val typeName: String,
    val description: String = "",
    val payloadSchema: JsonObject = JsonObject(emptyMap()),
    val example: JsonElement = JsonNull,
)

// ---------------------------------------------------------------------------
// Job result view (rendered in the UI)
// ---------------------------------------------------------------------------

/**
 * Client-side view of GET /api/v1/jobs/{id}/result:
 *  - inline results arrive as JSON and are pretty-printed;
 *  - file results arrive as bytes; the app shows the metadata
 *    (sha256 header + size) and leaves downloads to the web client.
 */
sealed interface JobResultView {
    data class Inline(val pretty: String) : JobResultView
    data class FileRef(val sha256: String?, val sizeBytes: Long?) : JobResultView
}
