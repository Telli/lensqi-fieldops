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
    com.lensqi.fieldops.data.diagnostics.DiagnosticHistory.record("api", error?.code ?: "http_error", e.code())
    ApiOutcome.Rejected(e.code(), error?.code ?: "http_${e.code()}", error?.message ?: (body ?: e.message()))
} catch (e: IOException) {
    com.lensqi.fieldops.data.diagnostics.DiagnosticHistory.record("api", "unreachable")
    ApiOutcome.Unreachable(e.message ?: e.javaClass.simpleName)
} catch (e: SerializationException) {
    // A response this client cannot read is a contract break, shown as such rather than guessed around.
    com.lensqi.fieldops.data.diagnostics.DiagnosticHistory.record("api", "client_contract_mismatch")
    ApiOutcome.Rejected(0, "client_contract_mismatch", e.message ?: "unreadable response")
}

/**
 * One Idempotency-Key per pending action. A retry after a lost response, a server error or a failed model call reuses the
 * key, so the server replays its outcome (or reuses the photo it saved) instead of acting twice; an answer, accepted or
 * refused, drops the key so the next tap is a new request.
 *
 * The action name must carry the state the command refers to (the claim, the photo, the server's event sequence): a key
 * is only ever reused for the same request to the same state, never for a new claim or a new photo.
 *
 * With a [SavedStateHandle] the pending keys survive process death and view model recreation, so a command whose answer
 * was lost just before Android killed the app is still replayed rather than repeated when the screen comes back.
 */
class RetryKeys(private val saved: androidx.lifecycle.SavedStateHandle? = null, private val slot: String = "retry-keys") {
    private val keys: HashMap<String, String> = saved?.get<HashMap<String, String>>(slot)?.let { HashMap(it) } ?: HashMap()
    private fun persist() { saved?.set(slot, HashMap(keys)) }
    @Synchronized fun keyFor(action: String): String = keys.getOrPut(action) { java.util.UUID.randomUUID().toString() }.also { persist() }
    /** The action moved somewhere else (queued offline under its key, say): the next tap is a new request. */
    @Synchronized fun drop(action: String) { keys.remove(action); persist() }
    /** Every pending action whose name starts with [prefix] is a new request from now on: the state it depended on changed elsewhere. */
    @Synchronized fun dropAll(prefix: String) { keys.keys.removeAll { it.startsWith(prefix) }; persist() }
    @Synchronized fun <T> settle(action: String, outcome: ApiOutcome<T>): ApiOutcome<T> {
        if (!outcome.retryable) { keys.remove(action); persist() }
        return outcome
    }
    /** Runs one command under its action's key and settles it. */
    suspend fun <T> command(action: String, call: suspend (key: String) -> T): ApiOutcome<T> = settle(action, apiCall { call(keyFor(action)) })
}
