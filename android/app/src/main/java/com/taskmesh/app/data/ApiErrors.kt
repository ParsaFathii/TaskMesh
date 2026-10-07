package com.taskmesh.app.data

import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Uniform, user-facing error messages. Reads the SPEC §8 error envelope
 * (`{"error":{"code","message"}}`) out of HTTP error bodies when present.
 */
object ApiErrors {

    fun message(throwable: Throwable): String = when (throwable) {
        is HttpException -> httpMessage(throwable)
        is ApiException -> throwable.apiMessage
            ?: "Server error (HTTP ${throwable.code})"
        is SerializationException -> "Malformed response from the server"
        is SocketTimeoutException -> "The server took too long to respond"
        is UnknownHostException -> "Could not reach the server — check the address and your connection"
        is ConnectException -> "Could not connect to the server"
        is IOException -> "Network error: ${throwable.message ?: "connection failed"}"
        else -> throwable.message ?: "Unexpected error"
    }

    private fun httpMessage(e: HttpException): String {
        val envelope = runCatching {
            e.response()?.errorBody()?.string()?.let { body ->
                JsonConfig.json.decodeFromString(ErrorEnvelopeDto.serializer(), body)
            }
        }.getOrNull()
        val serverMessage = envelope?.error?.message
        if (!serverMessage.isNullOrBlank()) {
            return serverMessage
        }
        return when (e.code()) {
            400 -> "Bad request (HTTP 400)"
            401 -> "Session expired or invalid — sign in again"
            403 -> "You do not have permission for this action (HTTP 403)"
            404 -> "Not found (HTTP 404)"
            409 -> "Conflict with existing data (HTTP 409)"
            429 -> "Too many requests — try again shortly"
            else -> "Server error (HTTP ${e.code()})"
        }
    }
}

/**
 * Failure type produced by raw OkHttp calls (the job result endpoint), mirroring
 * what [ApiErrors] does for Retrofit's [HttpException].
 */
class ApiException(
    val code: Int,
    val apiCode: String? = null,
    val apiMessage: String? = null,
) : Exception(apiMessage ?: "HTTP $code")
