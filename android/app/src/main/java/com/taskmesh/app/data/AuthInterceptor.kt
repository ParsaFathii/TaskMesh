package com.taskmesh.app.data

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Injects `Authorization: Bearer <token>` (from the [TokenStore]) into every
 * request and triggers a global logout when the server answers 401 anywhere
 * outside the login endpoint (expired/revoked JWT).
 *
 * Pure OkHttp — directly unit-testable with MockWebServer.
 */
class AuthInterceptor(
    private val tokenProvider: () -> String?,
    private val onUnauthorized: () -> Unit,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider()
        val request = if (token.isNullOrBlank()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        val response = chain.proceed(request)
        if (response.code == HTTP_UNAUTHORIZED && !isLoginRequest(request.url.encodedPath)) {
            onUnauthorized()
        }
        return response
    }

    private fun isLoginRequest(path: String): Boolean =
        path.endsWith("/auth/login")
}

private const val HTTP_UNAUTHORIZED = 401
