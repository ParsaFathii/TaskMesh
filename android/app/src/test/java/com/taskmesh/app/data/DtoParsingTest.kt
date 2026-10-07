package com.taskmesh.app.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DTO decoding against fixture JSON that mirrors the backend wire format
 * (SPEC §8/§9 and the Spring DTO records) exactly: camelCase keys, uppercase
 * enum names, ISO-8601 timestamps as strings, nullable optional fields.
 */
class DtoParsingTest {

    private val json = JsonConfig.json

    // -- auth ------------------------------------------------------------------

    @Test
    fun `login response parses with user brief`() {
        val text = """
            {
              "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbiJ9.sig",
              "tokenType": "Bearer",
              "expiresIn": 43200,
              "user": {"id": "11111111-1111-1111-1111-111111111111", "username": "admin", "role": "ADMIN"}
            }
        """.trimIndent()
        val dto = json.decodeFromString(LoginResponseDto.serializer(), text)

        assertEquals("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbiJ9.sig", dto.token)
        assertEquals("Bearer", dto.tokenType)
        assertEquals(43200L, dto.expiresIn)
        assertEquals("admin", dto.user.username)
        assertEquals("ADMIN", dto.user.role)
        assertEquals(
            UserDto(id = "11111111-1111-1111-1111-111111111111", username = "admin", role = "ADMIN"),
            dto.user.toUser(),
        )
    }

    @Test
    fun `me response parses with optional email and timestamps`() {
        val text = """
            {
              "id": "22222222-2222-2222-2222-222222222222",
              "username": "operator",
              "email": "operator@example.com",
              "role": "OPERATOR",
              "createdAt": "2026-01-06T09:00:00+00:00",
              "updatedAt": "2026-01-06T09:00:00+00:00"
            }
        """.trimIndent()
        val dto = json.decodeFromString(UserDto.serializer(), text)

        assertEquals("operator", dto.username)
        assertEquals("OPERATOR", dto.role)
        assertEquals("operator@example.com", dto.email)
        assertTrue(dto.isOperator)
        assertFalse(UserDto(id = "3", username = "user", role = "USER").isOperator)
    }

    // -- jobs --------------------------------------------------------------------

    @Test
    fun `job parses with every field populated`() {
        val text = """
            {
              "id": "33333333-3333-3333-3333-333333333333",
              "projectId": "44444444-4444-4444-4444-444444444444",
              "ownerId": "11111111-1111-1111-1111-111111111111",
              "type": "text_statistics",
              "priority": "HIGH",
              "payload": {"text": "hello taskmesh", "caseSensitive": false},
              "status": "RUNNING",
              "idempotencyKey": "submit-001",
              "createdAt": "2026-01-06T10:00:00+00:00",
              "queuedAt": "2026-01-06T10:00:01+00:00",
              "startedAt": "2026-01-06T10:00:02+00:00",
              "completedAt": null,
              "retryCount": 1,
              "maxRetries": 3,
              "timeoutSeconds": 120,
              "workerId": "55555555-5555-5555-5555-555555555555",
              "progress": 42,
              "cancelRequested": false,
              "lastError": null
            }
        """.trimIndent()
        val dto = json.decodeFromString(JobDto.serializer(), text)

        assertEquals(JobStatus.RUNNING, dto.status)
        assertEquals(JobPriority.HIGH, dto.priority)
        assertEquals(42, dto.progress)
        assertEquals("submit-001", dto.idempotencyKey)
        assertEquals(1, dto.retryCount)
        assertEquals("55555555-5555-5555-5555-555555555555", dto.workerId)
        assertNull(dto.completedAt)
        assertNull(dto.lastError)
        assertEquals("hello taskmesh", dto.payload["text"]?.jsonPrimitive?.content)
        assertNull(dto.worker) // list responses carry no worker summary
    }

    @Test
    fun `queued job parses with transient fields absent`() {
        val text = """
            {
              "id": "33333333-3333-3333-3333-333333333333",
              "projectId": "44444444-4444-4444-4444-444444444444",
              "ownerId": "11111111-1111-1111-1111-111111111111",
              "type": "cpu_benchmark",
              "priority": "NORMAL",
              "payload": {"workload": "primes", "durationSeconds": 5},
              "status": "QUEUED",
              "createdAt": "2026-01-06T10:00:00+00:00",
              "retryCount": 0,
              "maxRetries": 3,
              "timeoutSeconds": 120,
              "cancelRequested": false
            }
        """.trimIndent()
        val dto = json.decodeFromString(JobDto.serializer(), text)

        assertEquals(JobStatus.QUEUED, dto.status)
        assertEquals(JobPriority.NORMAL, dto.priority)
        assertNull(dto.progress)
        assertNull(dto.workerId)
        assertNull(dto.queuedAt)
        assertNull(dto.startedAt)
        assertNull(dto.idempotencyKey)
        assertFalse(dto.cancelRequested)
    }

    @Test
    fun `every job status round-trips`() {
        val statuses = listOf("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED", "RETRYING", "TIMED_OUT")
        for (status in statuses) {
            val text = """{"id":"1","projectId":"2","ownerId":"3","type":"hash_sha256","status":"$status"}"""
            assertEquals(status, json.decodeFromString(JobDto.serializer(), text).status.name)
        }
    }

    @Test
    fun `job detail envelope merges worker summary`() {
        val text = """
            {
              "job": {
                "id": "33333333-3333-3333-3333-333333333333",
                "projectId": "44444444-4444-4444-4444-444444444444",
                "ownerId": "11111111-1111-1111-1111-111111111111",
                "type": "image_resize",
                "priority": "CRITICAL",
                "payload": {"imageBase64": "aGk=", "width": 64, "height": 64},
                "status": "RUNNING",
                "createdAt": "2026-01-06T10:00:00+00:00",
                "retryCount": 0,
                "maxRetries": 2,
                "timeoutSeconds": 60,
                "cancelRequested": false,
                "workerId": "55555555-5555-5555-5555-555555555555",
                "progress": 7
              },
              "worker": {
                "id": "55555555-5555-5555-5555-555555555555",
                "name": "worker-1",
                "hostname": "host-a",
                "status": "BUSY"
              }
            }
        """.trimIndent()
        val detail = json.decodeFromString(JobDetailDto.serializer(), text)
        val merged = detail.job.copy(worker = detail.worker)

        assertEquals(JobStatus.RUNNING, merged.status)
        assertEquals(JobPriority.CRITICAL, merged.priority)
        assertEquals(7, merged.progress)
        assertEquals("worker-1", merged.worker?.name)
        assertEquals("host-a", merged.worker?.hostname)
        assertEquals(WorkerStatus.BUSY, merged.worker?.status)
    }

    @Test
    fun `job detail with null worker decodes`() {
        val text = """
            {
              "job": {
                "id": "1", "projectId": "2", "ownerId": "3", "type": "csv_analysis",
                "priority": "LOW", "payload": {"csv": "a,b\n1,2"}, "status": "QUEUED",
                "retryCount": 0, "maxRetries": 3, "timeoutSeconds": 120, "cancelRequested": false
              },
              "worker": null
            }
        """.trimIndent()
        val detail = json.decodeFromString(JobDetailDto.serializer(), text)
        assertNull(detail.worker)
    }

    // -- pagination --------------------------------------------------------------

    @Test
    fun `paginated jobs envelope parses`() {
        val text = """
            {
              "items": [
                {
                  "id": "1", "projectId": "2", "ownerId": "3", "type": "text_statistics",
                  "priority": "NORMAL", "payload": {"text": "x"}, "status": "SUCCEEDED",
                  "completedAt": "2026-01-06T10:05:00+00:00",
                  "retryCount": 0, "maxRetries": 3, "timeoutSeconds": 120, "cancelRequested": false
                },
                {
                  "id": "2", "projectId": "2", "ownerId": "3", "type": "hash_sha256",
                  "priority": "NORMAL", "payload": {"text": "y"}, "status": "FAILED",
                  "lastError": "boom", "retryCount": 3, "maxRetries": 3,
                  "timeoutSeconds": 120, "cancelRequested": false
                }
              ],
              "page": 0,
              "size": 25,
              "total": 137
            }
        """.trimIndent()
        val page = json.decodeFromString(PageDto.serializer(JobDto.serializer()), text)

        assertEquals(2, page.items.size)
        assertEquals(0, page.page)
        assertEquals(25, page.size)
        assertEquals(137L, page.total)
        assertEquals(JobStatus.SUCCEEDED, page.items[0].status)
        assertNull(page.items[0].progress)
        assertEquals("boom", page.items[1].lastError)
    }

    // -- errors --------------------------------------------------------------------

    @Test
    fun `error envelope parses`() {
        val text = """{"error": {"code": "NOT_FOUND", "message": "Job not found"}}"""
        val envelope = json.decodeFromString(ErrorEnvelopeDto.serializer(), text)

        assertEquals("NOT_FOUND", envelope.error?.code)
        assertEquals("Job not found", envelope.error?.message)
    }

    // -- workers --------------------------------------------------------------------

    @Test
    fun `workers page parses with capabilities and stale flag`() {
        val text = """
            {
              "items": [
                {
                  "id": "55555555-5555-5555-5555-555555555555",
                  "name": "worker-1",
                  "hostname": "host-a",
                  "version": "0.1.0",
                  "status": "BUSY",
                  "capabilities": ["csv_analysis", "hash_sha256", "text_statistics"],
                  "startedAt": "2026-01-06T08:00:00+00:00",
                  "lastHeartbeat": "2026-01-06T10:00:07+00:00",
                  "heartbeatIntervalS": 10,
                  "currentJobId": "33333333-3333-3333-3333-333333333333",
                  "controlPort": 9100,
                  "stale": false
                },
                {
                  "id": "66666666-6666-6666-6666-666666666666",
                  "name": "worker-2",
                  "hostname": "host-b",
                  "version": "0.1.0",
                  "status": "OFFLINE",
                  "capabilities": [],
                  "lastHeartbeat": "2026-01-06T09:00:00+00:00",
                  "heartbeatIntervalS": 10,
                  "stale": true
                }
              ],
              "page": 0,
              "size": 100,
              "total": 2
            }
        """.trimIndent()
        val page = json.decodeFromString(PageDto.serializer(WorkerDto.serializer()), text)

        assertEquals(2, page.items.size)
        val busy = page.items[0]
        assertEquals(WorkerStatus.BUSY, busy.status)
        assertEquals(3, busy.capabilities.size)
        assertEquals("csv_analysis", busy.capabilities[0])
        assertFalse(busy.stale)
        assertEquals("33333333-3333-3333-3333-333333333333", busy.currentJobId)
        val offline = page.items[1]
        assertEquals(WorkerStatus.OFFLINE, offline.status)
        assertTrue(offline.stale)
        assertTrue(offline.capabilities.isEmpty())
        assertNull(offline.currentJobId)
    }

    // -- job types (SPEC §9 catalog) -----------------------------------------------

    @Test
    fun `job type catalog parses schema and example`() {
        val text = """
            [
              {
                "type": "hash_sha256",
                "description": "SHA-256 hash of text or base64 content",
                "payloadSchema": {
                  "type": "object",
                  "properties": {
                    "contentBase64": {"type": "string"},
                    "text": {"type": "string"}
                  },
                  "required": [],
                  "oneOfFields": ["contentBase64", "text"]
                },
                "example": {"text": "hello taskmesh"}
              },
              {
                "type": "cpu_benchmark",
                "description": "CPU workload benchmark",
                "payloadSchema": {"type": "object", "properties": {}},
                "example": {"workload": "primes", "durationSeconds": 5}
              }
            ]
        """.trimIndent()
        val types = json.decodeFromString(
            ListSerializer(JobTypeDto.serializer()),
            text,
        )

        assertEquals(2, types.size)
        assertEquals("hash_sha256", types[0].typeName)
        assertEquals("CPU workload benchmark", types[1].description)
        assertEquals("hello taskmesh", types[0].example.jsonObject["text"]?.jsonPrimitive?.content)
        assertTrue(types[0].payloadSchema.containsKey("oneOfFields"))
    }

    // -- logs & attempts -----------------------------------------------------------

    @Test
    fun `job logs parse as plain array`() {
        val text = """
            [
              {"id": 12, "jobId": "33333333-3333-3333-3333-333333333333",
               "workerId": "55555555-5555-5555-5555-555555555555", "level": "INFO",
               "message": "claimed job", "metadata": null, "createdAt": "2026-01-06T10:00:02+00:00"},
              {"id": 11, "jobId": "33333333-3333-3333-3333-333333333333",
               "workerId": null, "level": "WARN",
               "message": "retry 1 scheduled", "metadata": {"backoffSeconds": 10},
               "createdAt": "2026-01-06T09:59:50+00:00"}
            ]
        """.trimIndent()
        val logs = json.decodeFromString(
            ListSerializer(JobLogDto.serializer()),
            text,
        )

        assertEquals(2, logs.size)
        assertEquals("INFO", logs[0].level)
        assertEquals("claimed job", logs[0].message)
        assertEquals(10L, logs[1].metadata?.get("backoffSeconds")?.jsonPrimitive?.long)
    }

    @Test
    fun `job attempts parse as plain array`() {
        val text = """
            [
              {"id": 7, "jobId": "33333333-3333-3333-3333-333333333333", "attemptNumber": 1,
               "workerId": "55555555-5555-5555-5555-555555555555",
               "startedAt": "2026-01-06T10:00:02+00:00",
               "finishedAt": "2026-01-06T10:00:04+00:00",
               "outcome": "SUCCEEDED", "error": null},
              {"id": 6, "jobId": "33333333-3333-3333-3333-333333333333", "attemptNumber": 0,
               "workerId": "66666666-6666-6666-6666-666666666666",
               "startedAt": "2026-01-06T09:59:00+00:00",
               "finishedAt": "2026-01-06T09:59:30+00:00",
               "outcome": "TIMED_OUT", "error": "hard timeout after 30s"}
            ]
        """.trimIndent()
        val attempts = json.decodeFromString(
            ListSerializer(JobAttemptDto.serializer()),
            text,
        )

        assertEquals(2, attempts.size)
        assertEquals(1, attempts[0].attemptNumber)
        assertEquals("SUCCEEDED", attempts[0].outcome)
        assertEquals("TIMED_OUT", attempts[1].outcome)
        assertEquals("hard timeout after 30s", attempts[1].error)
    }

    // -- projects --------------------------------------------------------------------

    @Test
    fun `project parses with defaults`() {
        val text = """
            {"id": "44444444-4444-4444-4444-444444444444", "name": "ETL",
             "description": "nightly pipeline", "ownerId": "11111111-1111-1111-1111-111111111111",
             "createdAt": "2026-01-06T08:00:00+00:00", "updatedAt": "2026-01-06T08:00:00+00:00"}
        """.trimIndent()
        val project = json.decodeFromString(ProjectDto.serializer(), text)

        assertEquals("ETL", project.name)
        assertEquals("nightly pipeline", project.description)

        val bare = json.decodeFromString(
            ProjectDto.serializer(),
            """{"id": "1", "name": "Bare", "ownerId": "2"}""",
        )
        assertEquals("", bare.description)
        assertNull(bare.createdAt)
    }

    // -- robustness -------------------------------------------------------------------

    @Test
    fun `unknown fields are ignored`() {
        val text = """
            {"id": "1", "projectId": "2", "ownerId": "3", "type": "json_transform",
             "priority": "NORMAL", "payload": {"input": {}, "operations": []},
             "status": "RETRYING", "retryCount": 2, "maxRetries": 3,
             "timeoutSeconds": 120, "cancelRequested": false,
             "futureBackendField": {"nested": [1, 2, 3]}}
        """.trimIndent()
        val dto = json.decodeFromString(JobDto.serializer(), text)

        assertEquals(JobStatus.RETRYING, dto.status)
        assertEquals(2, dto.retryCount)
    }

    @Test
    fun `cached user round-trips through JSON`() {
        val brief = UserBriefDto(id = "1", username = "user", role = "USER")
        val encoded = JsonConfig.json.encodeToString(UserBriefDto.serializer(), brief)

        val parsed = parseCachedUser(encoded)
        assertEquals(brief, parsed)
        assertNull(parseCachedUser(null))
        assertNull(parseCachedUser(""))
        assertNull(parseCachedUser("not json"))
    }

    @Test
    fun `json config parses and renders payload elements`() {
        val element = JsonConfig.parseElement("""{"text": "hello", "n": 5, "ok": true}""")
        assertNotNull(element)
        val obj = element?.jsonObject
        assertEquals("hello", obj?.get("text")?.jsonPrimitive?.content)
        assertEquals(5, obj?.get("n")?.jsonPrimitive?.int)
        assertEquals(true, obj?.get("ok")?.jsonPrimitive?.boolean)

        assertNull(JsonConfig.parseElement("not json"))
        assertNull(JsonConfig.parseElement(""))
        assertEquals("null", JsonConfig.renderPretty(null))
    }
}
