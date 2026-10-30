package com.lensqi.fieldops.data.api

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException

/** Outcome of one API call as the UI shows it: server codes are surfaced verbatim, never reinterpreted. */
sealed interface ApiOutcome<out T> {
    data class Ok<T>(val value: T) : ApiOutcome<T>
    data class Rejected(val httpStatus: Int, val code: String, val message: String) : ApiOutcome<Nothing>
    data class Unreachable(val message: String) : ApiOutcome<Nothing>
}

/**
 * Whether a retry should reuse the request's Idempotency-Key: the server was unreachable, failed, timed out, asked to slow
 * down, or its model call failed, so the command may have been applied or its photo saved. A refusal is final and a retry
 * is a new request.
 */
val ApiOutcome<*>.retryable: Boolean
    get() = this is ApiOutcome.Unreachable ||
        this is ApiOutcome.Rejected && (httpStatus >= 500 || httpStatus == 408 || httpStatus == 429 || code == "provider_unavailable" || code == "invalid_model_output")

private val errorJson = Json { ignoreUnknownKeys = true }

suspend fun <T> apiCall(block: suspend () -> T): ApiOutcome<T> = try {
    ApiOutcome.Ok(block())
} catch (e: HttpException) {
    val body = e.response()?.errorBody()?.string()
    val error = body?.let { runCatching { errorJson.decodeFromString(ApiError.serializer(), it) }.getOrNull() }
    ApiOutcome.Rejected(e.code(), error?.code ?: "http_${e.code()}", error?.message ?: (body ?: e.message()))
} catch (e: IOException) {
    ApiOutcome.Unreachable(e.message ?: e.javaClass.simpleName)
} catch (e: SerializationException) {
    // A response this client cannot read is a contract break, shown as such rather than guessed around.
    ApiOutcome.Rejected(0, "client_contract_mismatch", e.message ?: "unreadable response")
}
