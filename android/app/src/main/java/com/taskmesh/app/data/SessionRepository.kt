package com.taskmesh.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Session and API access point.
 *
 *  - Owns one [OkHttpClient] (with [AuthInterceptor]) and rebuilds only the
 *    Retrofit adapter whenever the base URL changes — the default is the
 *    Android emulator host loopback `http://10.0.2.2:8080`.
 *  - Exposes [sessionState] as a StateFlow: `LoggedIn` right after a successful
 *    login (and on process restart via the persisted token + cached user),
 *    `LoggedOut` on logout or on any 401 from the API (via [AuthInterceptor]).
 *  - Every API method returns [Result]; CancellationException is rethrown so
 *    coroutine cancellation propagates correctly.
 */
class SessionRepository(private val store: TokenStore) {

    private val sessionStateInternal = MutableStateFlow<SessionState>(SessionState.LoggedOut)
    val sessionState: StateFlow<SessionState> = sessionStateInternal.asStateFlow()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
        .addInterceptor(
            AuthInterceptor(
                tokenProvider = { store.token },
                onUnauthorized = { logout() },
            ),
        )
        .build()

    private val converterFactory = JsonConfig.json.asConverterFactory("application/json".toMediaType())

    private var currentApi: TaskMeshApi? = null
    private var currentApiUrl: String? = null

    init {
        restoreSession()
    }

    // -- configuration --------------------------------------------------------

    /** API origin without trailing slash, e.g. "http://10.0.2.2:8080". */
    val baseUrl: String
        get() = store.baseUrl ?: DEFAULT_BASE_URL

    /**
     * Normalizes and persists a new base URL; the Retrofit adapter is rebuilt
     * lazily on the next call. Returns false when the value is not a valid
     * http(s) URL (the caller keeps the previous configuration in that case).
     */
    fun setBaseUrl(value: String): Boolean {
        val normalized = normalizeBaseUrl(value) ?: return false
        store.baseUrl = normalized
        return true
    }

    var notificationsEnabled: Boolean
        get() = store.notificationsEnabled
        set(value) {
            store.notificationsEnabled = value
        }

    // -- session --------------------------------------------------------------

    /**
     * Signs in. When [baseUrlOverride] is a valid URL it is persisted first
     * (empty/blank keeps the current configuration).
     */
    suspend fun login(username: String, password: String, baseUrlOverride: String? = null): Result<Unit> {
        if (!baseUrlOverride.isNullOrBlank() && !setBaseUrl(baseUrlOverride)) {
            return Result.failure(IllegalArgumentException("Invalid server URL"))
        }
        return apiResult { api().login(LoginRequestDto(username = username, password = password)) }
            .mapCatching { response ->
                store.token = response.token
                store.cachedUserJson = JsonConfig.json
                    .encodeToString(UserBriefDto.serializer(), response.user)
                sessionStateInternal.value = SessionState.LoggedIn(response.user.toUser())
            }
    }

    fun logout() {
        store.clearSession()
        sessionStateInternal.value = SessionState.LoggedOut
    }

    /** Refreshes the session user from GET /api/v1/me. */
    suspend fun me(): Result<UserDto> = apiResult { api().me() }

    private fun restoreSession() {
        val token = store.token
        if (token.isNullOrBlank()) {
            return
        }
        val cached = parseCachedUser(store.cachedUserJson)
        if (cached == null) {
            // Token without identity (corrupted prefs) — treat as logged out.
            logout()
            return
        }
        sessionStateInternal.value = SessionState.LoggedIn(cached.toUser())
    }

    // -- projects ----------------------------------------------------------------

    suspend fun projects(page: Int = 0, size: Int = 25): Result<PageDto<ProjectDto>> =
        apiResult { api().projects(page = page, size = size) }

    suspend fun createProject(name: String, description: String): Result<ProjectDto> =
        apiResult { api().createProject(ProjectCreateRequest(name = name, description = description)) }

    // -- jobs ----------------------------------------------------------------

    suspend fun jobs(
        projectId: String? = null,
        status: JobStatus? = null,
        type: String? = null,
        priority: JobPriority? = null,
        page: Int = 0,
        size: Int = 25,
    ): Result<PageDto<JobDto>> = apiResult {
        api().jobs(
            projectId = projectId,
            status = status?.name,
            type = type,
            priority = priority?.name,
            page = page,
            size = size,
        )
    }

    suspend fun createJob(request: JobCreateRequest): Result<JobDto> =
        apiResult { api().createJob(request) }

    /** Job detail: merges the {job, worker} envelope into a single [JobDto]. */
    suspend fun job(jobId: String): Result<JobDto> = apiResult {
        val detail = api().jobDetail(jobId)
        detail.job.copy(worker = detail.worker)
    }

    suspend fun cancelJob(jobId: String): Result<JobDto> =
        apiResult { api().cancelJob(jobId) }

    suspend fun retryJob(jobId: String): Result<JobDto> =
        apiResult { api().retryJob(jobId) }

    suspend fun logs(jobId: String, level: String? = null, limit: Int? = null): Result<List<JobLogDto>> =
        apiResult { api().jobLogs(id = jobId, level = level, limit = limit) }

    suspend fun attempts(jobId: String): Result<List<JobAttemptDto>> =
        apiResult { api().attempts(jobId) }

    // -- workers & catalog ------------------------------------------------------

    suspend fun workers(page: Int = 0, size: Int = 100): Result<List<WorkerDto>> = apiResult {
        api().workers(page = page, size = size).items
    }

    suspend fun jobTypes(): Result<List<JobTypeDto>> = apiResult { api().jobTypes() }

    // -- job result (raw OkHttp; content-type driven) ----------------------------

    /**
     * GET /api/v1/jobs/{id}/result. Inline results (JSON content type) are
     * pretty-printed; file results are represented by their metadata
     * (X-Job-Result-SHA256 header + size) without downloading the bytes.
     */
    suspend fun jobResult(jobId: String): Result<JobResultView> {
        val token = store.token
        val request = Request.Builder()
            .url(httpBaseUrl() + "api/v1/jobs/$jobId/result")
            .get()
            .apply {
                if (!token.isNullOrBlank()) {
                    header("Authorization", "Bearer $token")
                }
            }
            .build()
        return try {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    val exception = response.toApiException()
                    Result.failure(exception)
                } else {
                    val contentType = response.header("Content-Type") ?: ""
                    if (contentType.contains("json", ignoreCase = true)) {
                        val bodyText = response.body?.string() ?: "null"
                        val element = JsonConfig.parseElement(bodyText) ?: JsonNull
                        Result.success(JobResultView.Inline(JsonConfig.renderPretty(element)))
                    } else {
                        val sha256 = response.header("X-Job-Result-SHA256")
                        val size = response.header("Content-Length")?.toLongOrNull()
                            ?: response.body?.contentLength()?.takeIf { it >= 0 }
                        Result.success(JobResultView.FileRef(sha256 = sha256, sizeBytes = size))
                    }
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    // -- internals ----------------------------------------------------------------

    private fun api(): TaskMeshApi {
        val url = httpBaseUrl()
        synchronized(this) {
            val existing = currentApi
            if (existing == null || currentApiUrl != url) {
                val rebuilt = Retrofit.Builder()
                    .baseUrl(url)
                    .client(client)
                    .addConverterFactory(converterFactory)
                    .build()
                    .create(TaskMeshApi::class.java)
                currentApi = rebuilt
                currentApiUrl = url
                return rebuilt
            }
            return existing
        }
    }

    private fun httpBaseUrl(): String {
        val raw = baseUrl.trim()
        return if (raw.endsWith("/")) raw else "$raw/"
    }

    private fun response.toApiException(): ApiException {
        val bodyText = runCatching { body?.string() }.getOrNull()
        val envelope = bodyText?.let { text ->
            runCatching {
                JsonConfig.json.decodeFromString(ErrorEnvelopeDto.serializer(), text)
            }.getOrNull()
        }
        return ApiException(
            code = code,
            apiCode = envelope?.error?.code,
            apiMessage = envelope?.error?.message,
        )
    }

    companion object {
        /** Android emulator alias for the host machine's loopback. */
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8080"

        private const val CONNECT_TIMEOUT_S = 15L
        private const val READ_TIMEOUT_S = 30L
        private const val WRITE_TIMEOUT_S = 30L

        /** Trims + validates; returns the display form (no trailing slash) or null. */
        fun normalizeBaseUrl(value: String): String? {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) {
                return null
            }
            val url = trimmed.toHttpUrlOrNull() ?: return null
            if (url.scheme != "http" && url.scheme != "https") {
                return null
            }
            return trimmed.trimEnd('/')
        }
    }
}

/** Session states observed by the UI (start destination, role gating, logout). */
sealed interface SessionState {
    data class LoggedIn(val user: UserDto) : SessionState
    data object LoggedOut : SessionState
}

/** Result wrapper that never swallows coroutine cancellation. */
suspend fun <T> apiResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (c: CancellationException) {
    throw c
} catch (t: Throwable) {
    Result.failure(t)
}

/** Suspend adapter for OkHttp calls (cancellation propagates into call cancel). */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        },
    )
    continuation.invokeOnCancellation {
        runCatching { cancel() }
    }
}
