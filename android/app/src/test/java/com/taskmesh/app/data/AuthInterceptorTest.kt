package com.taskmesh.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Bearer injection + global 401 logout callback, exercised through a real
 * OkHttp stack against MockWebServer.
 */
class AuthInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    private var token: String? = null
    private var unauthorizedCalls: Int = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        unauthorizedCalls = 0
        token = null
        val interceptor = AuthInterceptor(
            tokenProvider = { token },
            onUnauthorized = { unauthorizedCalls++ },
        )
        client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun get(path: String) {
        val request = Request.Builder()
            .url(server.url(path))
            .get()
            .build()
        client.newCall(request).execute().use { /* body consumed/closed */ }
    }

    @Test
    fun `adds bearer header when token is present`() {
        token = "jwt-token-123"
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        get("api/v1/jobs")

        val recorded = server.takeRequest()
        assertEquals("Bearer jwt-token-123", recorded.headers["Authorization"])
    }

    @Test
    fun `no authorization header when anonymous`() {
        token = null
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        get("api/v1/jobs")

        val recorded = server.takeRequest()
        assertNull(recorded.headers["Authorization"])
    }

    @Test
    fun `blank token is not sent`() {
        token = "   "
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        get("api/v1/jobs")

        val recorded = server.takeRequest()
        assertNull(recorded.headers["Authorization"])
    }

    @Test
    fun `401 triggers the logout callback`() {
        token = "expired-token"
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"code":"UNAUTHORIZED","message":"Invalid token"}}"""),
        )

        get("api/v1/jobs")

        assertEquals(1, unauthorizedCalls)
    }

    @Test
    fun `successful responses do not trigger the callback`() {
        token = "valid"
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        get("api/v1/jobs")

        assertEquals(0, unauthorizedCalls)
    }

    @Test
    fun `401 on the login path does not trigger logout`() {
        token = null
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"code":"UNAUTHORIZED","message":"Bad credentials"}}"""),
        )

        get("api/v1/auth/login")

        assertEquals(0, unauthorizedCalls)
        // The request itself is still observable (the caller reports failure).
        assertNotNull(server.takeRequest())
    }

    @Test
    fun `other error codes do not trigger logout`() {
        token = "valid"
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"NOT_FOUND"}}"""))
        get("api/v1/jobs/999")
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"FORBIDDEN"}}"""))
        get("api/v1/workers")
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        get("api/v1/jobs")

        assertEquals(0, unauthorizedCalls)
        assertTrue(server.takeRequest() != null)
    }
}
