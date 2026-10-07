package com.taskmesh.app.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end JVM flows through the REAL stack — Retrofit + kotlinx-serialization
 * converter + OkHttp + AuthInterceptor + TokenStore + SessionRepository —
 * against MockWebServer.
 */
class ApiFlowTest {

    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryTokenStore
    private lateinit var repo: SessionRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = InMemoryTokenStore()
        store.baseUrl = server.url("/").toString().trimEnd('/')
        repo = SessionRepository(store)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // -- helpers -------------------------------------------------------------

    private fun login() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(LOGIN_BODY))
        val result = runBlocking { repo.login("admin", "TaskMesh!Admin") }
        assertTrue("login should succeed", result.isSuccess)
        server.takeRequest() // consume the login request
    }

    // -- login ------------------------------------------------------------------

    @Test
    fun `login stores token and cached user and flips session state`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(LOGIN_BODY))

        val result = runBlocking { repo.login("admin", "TaskMesh!Admin") }

        assertTrue(result.isSuccess)
        assertEquals("jwt-abc", store.token)
        val state = repo.sessionState.value
        assertTrue(state is SessionState.LoggedIn)
        state as SessionState.LoggedIn
        assertEquals("admin", state.user.username)
        assertEquals("ADMIN", state.user.role)
        val cached = parseCachedUser(store.cachedUserJson)
        assertEquals("admin", cached?.username)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/auth/login", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"username\":\"admin\""))
        assertTrue(body.contains("\"password\":\"TaskMesh!Admin\""))
        assertNull(recorded.headers["Authorization"]) // no token while logging in
    }

    @Test
    fun `login with invalid base url override fails without a request`() {
        val result = runBlocking { repo.login("u", "p", baseUrlOverride = "not a url") }

        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
        assertEquals(SessionState.LoggedOut, repo.sessionState.value)
    }

    @Test
    fun `login with base url override persists it`() {
        val freshStore = InMemoryTokenStore()
        val freshRepo = SessionRepository(freshStore)
        server.enqueue(MockResponse().setResponseCode(200).setBody(LOGIN_BODY))

        val override = server.url("/").toString().trimEnd('/')
        val result = runBlocking { freshRepo.login("u", "p", baseUrlOverride = override) }

        assertTrue(result.isSuccess)
        assertEquals(override, freshStore.baseUrl)
        assertEquals(override, freshRepo.baseUrl)
    }

    @Test
    fun `failed login keeps the session logged out`() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"code":"UNAUTHORIZED","message":"Bad credentials"}}"""),
        )

        val result = runBlocking { repo.login("admin", "wrong") }

        assertTrue(result.isFailure)
        assertEquals(SessionState.LoggedOut, repo.sessionState.value)
        assertNull(store.token)
    }

    // -- authenticated requests ---------------------------------------------------

    @Test
    fun `requests after login carry the bearer token`() {
        login()
        server.enqueue(MockResponse().setResponseCode(200).setBody(PAGE_BODY))

        val result = runBlocking { repo.jobs(page = 0, size = 50) }

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("Bearer jwt-abc", recorded.headers["Authorization"])
        assertEquals(1L, result.getOrNull()?.total)
    }

    @Test
    fun `jobs request carries filters and pagination`() {
        login()
        server.enqueue(MockResponse().setResponseCode(200).setBody(PAGE_BODY))

        runBlocking {
            repo.jobs(projectId = "p-1", status = JobStatus.RUNNING, page = 0, size = 50)
        }

        val recorded = server.takeRequest()
        val path = recorded.path ?: ""
        assertTrue(path.startsWith("/api/v1/jobs?"))
        assertTrue(path.contains("projectId=p-1"))
        assertTrue(path.contains("status=RUNNING"))
        assertTrue(path.contains("page=0"))
        assertTrue(path.contains("size=50"))
        assertFalse(path.contains("type="))
        assertFalse(path.contains("priority="))
    }

    @Test
    fun `cancel posts to the cancel path and parses the response`() {
        login()
        server.enqueue(MockResponse().setResponseCode(200).setBody(JOB_BODY))

        val result = runBlocking { repo.cancelJob("job-1") }

        assertTrue(result.isSuccess)
        assertEquals(JobStatus.SUCCEEDED, result.getOrNull()?.status)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/jobs/job-1/cancel", recorded.path)
        assertEquals("Bearer jwt-abc", recorded.headers["Authorization"])
    }

    @Test
    fun `retry posts to the retry path`() {
        login()
        server.enqueue(MockResponse().setResponseCode(200).setBody(JOB_BODY))

        val result = runBlocking { repo.retryJob("job-1") }

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/jobs/job-1/retry", recorded.path)
    }

    @Test
    fun `job detail merges the worker summary`() {
        login()
        server.enqueue(MockResponse().setResponseCode(200).setBody(DETAIL_BODY))

        val result = runBlocking { repo.job("job-1") }

        assertTrue(result.isSuccess)
        val job = result.getOrThrow()
        assertEquals(JobStatus.RUNNING, job.status)
        assertEquals(42, job.progress)
        assertEquals("worker-1", job.worker?.name)
        assertEquals(WorkerStatus.BUSY, job.worker?.status)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/jobs/job-1", recorded.path)
    }

    @Test
    fun `create job posts the full submission body`() {
        login()
        server.enqueue(MockResponse().setResponseCode(201).setBody(JOB_BODY))

        val request = JobCreateRequest(
            projectId = "p-1",
            type = "text_statistics",
            priority = JobPriority.HIGH,
            payload = buildJsonObject { put("text", "hello taskmesh") },
            maxRetries = 2,
            timeoutSeconds = 60,
            idempotencyKey = "key-001",
        )
        val result = runBlocking { repo.createJob(request) }

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/jobs", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"projectId\":\"p-1\""))
        assertTrue(body.contains("\"type\":\"text_statistics\""))
        assertTrue(body.contains("\"priority\":\"HIGH\""))
        assertTrue(body.contains("hello taskmesh"))
        assertTrue(body.contains("\"maxRetries\":2"))
        assertTrue(body.contains("\"timeoutSeconds\":60"))
        assertTrue(body.contains("\"idempotencyKey\":\"key-001\""))
    }

    @Test
    fun `create project posts name and description`() {
        login()
        server.enqueue(MockResponse().setResponseCode(201).setBody(PROJECT_BODY))

        val result = runBlocking { repo.createProject("ETL", "nightly pipeline") }

        assertTrue(result.isSuccess)
        assertEquals("ETL", result.getOrNull()?.name)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/projects", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"name\":\"ETL\""))
        assertTrue(body.contains("\"description\":\"nightly pipeline\""))
    }

    @Test
    fun `me refreshes the current user`() {
        login()
        server.enqueue(MockResponse().setResponseCode(200).setBody(ME_BODY))

        val result = runBlocking { repo.me() }

        assertTrue(result.isSuccess)
        assertEquals("operator@example.com", result.getOrNull()?.email)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/me", recorded.path)
    }

    @Test
    fun `workers unwraps the page envelope`() {
        login()
        server.enqueue(MockResponse().setResponseCode(200).setBody(WORKERS_BODY))

        val result = runBlocking { repo.workers() }

        assertTrue(result.isSuccess)
        val workers = result.getOrThrow()
        assertEquals(1, workers.size)
        assertEquals("worker-1", workers[0].name)
        assertFalse(workers[0].stale)
        val recorded = server.takeRequest()
        assertTrue(recorded.path.orEmpty().startsWith("/api/v1/workers?"))
        assertTrue(recorded.path.orEmpty().contains("size=100"))
    }

    @Test
    fun `job types parses the catalog`() {
        login()
        server.enqueue(MockResponse().setResponseCode(200).setBody(JOB_TYPES_BODY))

        val result = runBlocking { repo.jobTypes() }

        assertTrue(result.isSuccess)
        val types = result.getOrThrow()
        assertEquals(7, types.size)
        assertEquals("csv_analysis", types.first().typeName)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/job-types", recorded.path)
    }

    // -- 401 / errors ---------------------------------------------------------------

    @Test
    fun `401 from an api call logs the session out and clears the token`() {
        login()
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"code":"UNAUTHORIZED","message":"Invalid token"}}"""),
        )

        val result = runBlocking { repo.jobs() }

        assertTrue(result.isFailure)
        assertEquals(SessionState.LoggedOut, repo.sessionState.value)
        assertNull(store.token)
        assertNull(store.cachedUserJson)
    }

    @Test
    fun `error envelope message surfaces through ApiErrors`() {
        login()
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"code":"NOT_FOUND","message":"Job missing not found"}}"""),
        )

        val result = runBlocking { repo.job("missing") }

        assertTrue(result.isFailure)
        val message = ApiErrors.message(result.exceptionOrNull()!!)
        assertEquals("Job missing not found", message)
    }

    @Test
    fun `transport failures produce a friendly message`() {
        login()
        // Respond but kill the connection pattern is complex; instead point a
        // fresh repository at an unreachable port.
        val deadStore = InMemoryTokenStore()
        deadStore.baseUrl = "http://127.0.0.1:1"
        val deadRepo = SessionRepository(deadStore)

        val result = runBlocking { deadRepo.jobs() }

        assertTrue(result.isFailure)
        val message = ApiErrors.message(result.exceptionOrNull()!!)
        assertTrue(message.isNotBlank())
    }

    // -- job result (raw OkHttp path) ------------------------------------------------

    @Test
    fun `inline job result pretty-prints json`() {
        login()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"sha256":"abc123","bytes":3,"source":"text"}"""),
        )

        val result = runBlocking { repo.jobResult("job-1") }

        assertTrue(result.isSuccess)
        val view = result.getOrThrow()
        assertTrue(view is JobResultView.Inline)
        view as JobResultView.Inline
        assertTrue(view.pretty.contains("abc123"))
        val recorded = server.takeRequest()
        assertEquals("/api/v1/jobs/job-1/result", recorded.path)
        assertEquals("Bearer jwt-abc", recorded.headers["Authorization"])
    }

    @Test
    fun `file job result surfaces sha256 and size without content`() {
        login()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setHeader("X-Job-Result-SHA256", "deadbeefcafe")
                .setBody("0123456789"),
        )

        val result = runBlocking { repo.jobResult("job-1") }

        assertTrue(result.isSuccess)
        val view = result.getOrThrow()
        assertTrue(view is JobResultView.FileRef)
        view as JobResultView.FileRef
        assertEquals("deadbeefcafe", view.sha256)
        assertEquals(10L, view.sizeBytes)
    }

    @Test
    fun `missing job result fails with the server message`() {
        login()
        server.enqueue(
            MockResponse().setResponseCode(404)
                .setBody("""{"error":{"code":"NOT_FOUND","message":"Result not found"}}"""),
        )

        val result = runBlocking { repo.jobResult("job-1") }

        assertTrue(result.isFailure)
        val message = ApiErrors.message(result.exceptionOrNull()!!)
        assertEquals("Result not found", message)
    }

    // -- configuration --------------------------------------------------------------

    @Test
    fun `base url normalization`() {
        assertTrue(repo.setBaseUrl("http://10.0.2.2:9999/"))
        assertEquals("http://10.0.2.2:9999", repo.baseUrl)
        assertTrue(repo.setBaseUrl("  https://mesh.example.com  "))
        assertEquals("https://mesh.example.com", repo.baseUrl)
        assertFalse(repo.setBaseUrl("not a url"))
        assertFalse(repo.setBaseUrl(""))
        assertFalse(repo.setBaseUrl("ftp://example.com"))
    }

    @Test
    fun `default base url is the emulator host loopback`() {
        val fresh = SessionRepository(InMemoryTokenStore())
        assertEquals(SessionRepository.DEFAULT_BASE_URL, fresh.baseUrl)
    }

    @Test
    fun `session restores from a persisted token and cached user`() {
        val persisted = InMemoryTokenStore()
        persisted.token = "jwt-abc"
        persisted.cachedUserJson = """{"id":"u-1","username":"admin","role":"ADMIN"}"""

        val restored = SessionRepository(persisted)

        val state = restored.sessionState.value
        assertTrue(state is SessionState.LoggedIn)
        state as SessionState.LoggedIn
        assertEquals("admin", state.user.username)
    }

    @Test
    fun `token without cached user logs out`() {
        val persisted = InMemoryTokenStore()
        persisted.token = "jwt-abc"

        val restored = SessionRepository(persisted)

        assertEquals(SessionState.LoggedOut, restored.sessionState.value)
        assertNull(persisted.token)
    }

    // -- fixtures -------------------------------------------------------------------

    private class InMemoryTokenStore : TokenStore {
        override var token: String? = null
        override var baseUrl: String? = null
        override var notificationsEnabled: Boolean = true
        override var cachedUserJson: String? = null

        override fun clearSession() {
            token = null
            cachedUserJson = null
        }
    }

    private companion object {
        val LOGIN_BODY = """
            {"token":"jwt-abc","tokenType":"Bearer","expiresIn":43200,
             "user":{"id":"u-1","username":"admin","role":"ADMIN"}}
        """.trimIndent()

        val JOB_BODY = """
            {"id":"job-1","projectId":"p-1","ownerId":"u-1","type":"text_statistics",
             "priority":"HIGH","payload":{"text":"hi"},"status":"SUCCEEDED",
             "retryCount":0,"maxRetries":3,"timeoutSeconds":120,"cancelRequested":false,
             "completedAt":"2026-01-06T10:05:00+00:00"}
        """.trimIndent()

        val PAGE_BODY = """
            {"items":[
              {"id":"job-1","projectId":"p-1","ownerId":"u-1","type":"text_statistics",
               "priority":"NORMAL","payload":{"text":"hi"},"status":"RUNNING",
               "createdAt":"2026-01-06T10:00:00+00:00","retryCount":0,"maxRetries":3,
               "timeoutSeconds":120,"cancelRequested":false,"progress":42}
            ],"page":0,"size":50,"total":1}
        """.trimIndent()

        val DETAIL_BODY = """
            {"job":{
              "id":"job-1","projectId":"p-1","ownerId":"u-1","type":"image_resize",
              "priority":"NORMAL","payload":{"imageBase64":"aGk=","width":64,"height":64},
              "status":"RUNNING","createdAt":"2026-01-06T10:00:00+00:00",
              "retryCount":0,"maxRetries":3,"timeoutSeconds":120,"cancelRequested":false,
              "workerId":"w-1","progress":42},
             "worker":{"id":"w-1","name":"worker-1","hostname":"host-a","status":"BUSY"}}
        """.trimIndent()

        val PROJECT_BODY = """
            {"id":"p-1","name":"ETL","description":"nightly pipeline","ownerId":"u-1",
             "createdAt":"2026-01-06T08:00:00+00:00","updatedAt":"2026-01-06T08:00:00+00:00"}
        """.trimIndent()

        val ME_BODY = """
            {"id":"u-1","username":"admin","email":"operator@example.com","role":"ADMIN",
             "createdAt":"2026-01-06T08:00:00+00:00","updatedAt":"2026-01-06T08:00:00+00:00"}
        """.trimIndent()

        val WORKERS_BODY = """
            {"items":[
              {"id":"w-1","name":"worker-1","hostname":"host-a","version":"0.1.0",
               "status":"BUSY","capabilities":["csv_analysis","hash_sha256"],
               "startedAt":"2026-01-06T08:00:00+00:00","lastHeartbeat":"2026-01-06T10:00:07+00:00",
               "heartbeatIntervalS":10,"currentJobId":"job-1","controlPort":9100,"stale":false}
            ],"page":0,"size":100,"total":1}
        """.trimIndent()

        val JOB_TYPES_BODY = """
            [
              {"type":"csv_analysis","description":"CSV analysis","payloadSchema":{},"example":{}},
              {"type":"json_transform","description":"JSON transform","payloadSchema":{},"example":{}},
              {"type":"image_resize","description":"Image resize","payloadSchema":{},"example":{}},
              {"type":"hash_sha256","description":"SHA-256 hash","payloadSchema":{},"example":{}},
              {"type":"text_statistics","description":"Text statistics","payloadSchema":{},"example":{}},
              {"type":"archive_inspection","description":"Archive inspection","payloadSchema":{},"example":{}},
              {"type":"cpu_benchmark","description":"CPU benchmark","payloadSchema":{},"example":{}}
            ]
        """.trimIndent()
    }
}
