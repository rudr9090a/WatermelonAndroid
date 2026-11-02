package com.hydrogenlabs.watermelon_android.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException

@Serializable
private data class ErrorPayload(val detail: String? = null, val error: String? = null)

class ApiFailure(val status: Int, override val message: String) : Exception(message)

fun Throwable.toApiFailure(): ApiFailure = when (this) {
    is ApiFailure -> this
    is HttpException -> {
        val body = runCatching { response()?.errorBody()?.string() }.getOrNull()
        val detail = body?.let { runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<ErrorPayload>(it) }.getOrNull() }
        val fallback = when (code()) {
            400 -> "The request was invalid."
            401 -> "Your session has expired. Please sign in again."
            403 -> "You do not have permission to do that."
            404 -> "That resource was not found."
            409 -> "That action conflicts with the current state."
            in 500..599 -> "The server is unavailable right now."
            else -> "The server returned HTTP ${code()}."
        }
        ApiFailure(code(), detail?.detail ?: detail?.error ?: fallback)
    }
    is IOException -> ApiFailure(0, "Could not reach the server. Check your connection.")
    else -> ApiFailure(0, message ?: "Something went wrong. Please try again.")
}
