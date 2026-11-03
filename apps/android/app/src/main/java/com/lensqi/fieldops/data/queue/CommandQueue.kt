package com.lensqi.fieldops.data.queue

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.lensqi.fieldops.data.InspectionCache
import com.lensqi.fieldops.data.api.ApiOutcome
import com.lensqi.fieldops.data.api.FieldOpsApi
import com.lensqi.fieldops.data.api.apiCall
import com.lensqi.fieldops.data.api.retryable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Result of draining the queue once. */
enum class DrainResult { Empty, Offline }

/** What the queue does with one send: keep a command (and its photo) after a transient failure, drop it only once refused. */
enum class Disposition { Sent, RetryLater, Refused }

fun dispositionOf(outcome: ApiOutcome<*>): Disposition = when {
    outcome is ApiOutcome.Ok -> Disposition.Sent
    outcome.retryable -> Disposition.RetryLater
    else -> Disposition.Refused
}

/** The bytes are neither JPEG nor PNG (a HEIC or WebP import, say); nothing is saved. */
class UnsupportedImageException : IllegalArgumentException("Only JPEG or PNG images can be evidence")

/** A task screen owns a server-bound view of the durable queue. */
interface StepCheckQueue {
    fun observe(sessionId: String): Flow<List<PendingCommand>>
    /** [source] is the capture device ("Phone", "Glasses", "MockWearables"); it is sent with the photo when the queue drains. */
    suspend fun enqueueStepCheck(taskId: String, stepId: String, key: String, jpeg: ByteArray, source: String = "Phone"): PendingCommand
    suspend fun dismiss(command: PendingCommand)
    suspend fun retry(command: PendingCommand) {}
}

@Singleton
class CommandQueue internal constructor(
    private val directory: File,
    private val dao: PendingCommandDao,
    private val apiFor: (String) -> FieldOpsApi,
    private val cache: InspectionCache,
    /** Where the camera and imports write temporary files; a source there is deleted once its saved copy exists. */
    private val scratch: File? = null,
    private val json: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val schedule: () -> Unit,
) {
    @Inject constructor(@ApplicationContext context: Context, dao: PendingCommandDao,
        factory: com.lensqi.fieldops.di.ApiFactory, cache: InspectionCache, json: kotlinx.serialization.json.Json) :
        this(File(context.filesDir, "captures"), dao, factory::forServer, cache, context.cacheDir, json, { scheduleSync(context) })

    private val mutex = Mutex()
    fun observe(sessionId: String, server: String) = dao.observe(sessionId, server)
    fun requestSync() = schedule()
    fun forServer(server: String): StepCheckQueue = object : StepCheckQueue {
        override fun observe(sessionId: String) = this@CommandQueue.observe(sessionId, server)
        override suspend fun enqueueStepCheck(taskId: String, stepId: String, key: String, jpeg: ByteArray, source: String) =
            save(PendingCommand(key, taskId, CommandKind.StepCheckPhoto, System.currentTimeMillis(), source = source, stepId = stepId, serverBase = server), jpeg)
        override suspend fun dismiss(command: PendingCommand) = this@CommandQueue.dismiss(command)
        override suspend fun retry(command: PendingCommand) { this@CommandQueue.retry(command, server) }
    }

    suspend fun enqueuePhoto(inspectionId: String, source: File, origin: String, server: String,
        observedAt: Instant = Instant.now(), afterRepair: Boolean = false, measured: String? = null): PendingCommand =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            save(PendingCommand(UUID.randomUUID().toString(), inspectionId, CommandKind.UploadPhoto, System.currentTimeMillis(),
                source = origin, observedAt = observedAt.toString(), serverBase = server, afterRepair = afterRepair, measured = measured), source.readBytes())
                .also { if (scratch != null && source.parentFile == scratch) source.delete() }
        }

    /**
     * An inspection command the server could not be reached for, kept under the Idempotency-Key the screen already used,
     * so the replay is the same request. Sent in capture order after the photos before it; a refusal (the server moved on)
     * is kept with its code until the technician removes it.
     */
    suspend fun enqueueCommand(inspectionId: String, kind: CommandKind, key: String, payload: String?, server: String): PendingCommand =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            require(kind.isJsonCommand) { "only JSON commands are queued this way" }
            PendingCommand(key, inspectionId, kind, System.currentTimeMillis(), serverBase = server, payload = payload).also { dao.insert(it); schedule() }
        }

    private suspend fun save(command: PendingCommand, bytes: ByteArray): PendingCommand =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val media = mediaTypeOf(bytes) ?: throw UnsupportedImageException()
            directory.mkdirs()
            val target = File(directory, "${command.id}.${if (media == "image/png") "png" else "jpg"}")
            target.writeBytes(bytes)
            val saved = command.copy(filePath = target.path, mediaType = media, sha256 = sha256(bytes))
            dao.insert(saved)
            schedule()
            saved
        }

    suspend fun dismiss(command: PendingCommand) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        dao.delete(command.id)
        command.filePath?.let { File(it).delete() }
        Unit
    }

    /** Refused photos stay on disk until an explicit retry or removal; unknown legacy destinations require user choice. */
    suspend fun retry(command: PendingCommand, server: String) {
        require(command.serverBase == null || command.serverBase == server)
        dao.retry(command.id, server)
        schedule()
    }

    /** Sends in capture order per server; a server that cannot take a command now is skipped, so it never holds up another. */
    suspend fun drain(): DrainResult = mutex.withLock {
        val unavailable = mutableSetOf<String>()
        var next = dao.nextPending(emptyList())
        while (next != null) {
            val command = next
            dao.countAttempt(command.id)
            val outcome = send(command)
            when (dispositionOf(outcome)) {
                Disposition.Sent -> {
                    if (command.kind == CommandKind.UploadPhoto) dao.uploaded(command.id, (outcome as ApiOutcome.Ok).value!!)
                    else dismiss(command)
                }
                Disposition.RetryLater -> unavailable += requireNotNull(command.serverBase)
                Disposition.Refused -> (outcome as ApiOutcome.Rejected).let { dao.reject(command.id, it.code, it.message) }
            }
            next = dao.nextPending(unavailable.toList())
        }
        if (unavailable.isEmpty()) DrainResult.Empty else DrainResult.Offline
    }

    private suspend fun send(c: PendingCommand): ApiOutcome<String?> {
        val server = c.serverBase ?: return ApiOutcome.Rejected(0, "server_unbound", "Choose the original server before retrying this saved photo")
        val api = apiFor(server)
        if (c.kind.isJsonCommand) return sendCommand(api, c, server)
        val file = c.filePath?.let(::File)?.takeIf { it.isFile }
            ?: return ApiOutcome.Rejected(0, "photo_missing", "The saved photo could not be found; capture it again")
        val part = MultipartBody.Part.createFormData("image", file.name, file.asRequestBody(requireNotNull(c.mediaType).toMediaType()))
        return when (c.kind) {
            CommandKind.UploadPhoto -> when (val r = apiCall { api.uploadObservation(c.sessionId, c.id, part, requireNotNull(c.source).toRequestBody(), requireNotNull(c.observedAt).toRequestBody(), c.measured?.toRequestBody()) }) {
                is ApiOutcome.Ok -> {
                    if (r.value.sha256 != c.sha256) ApiOutcome.Rejected(0, "client_digest_mismatch", "Uploaded bytes did not match the saved photo")
                    else { cache.put(r.value.state, server); ApiOutcome.Ok(r.value.evidenceId) }
                }
                is ApiOutcome.Rejected -> r
                is ApiOutcome.Unreachable -> r
            }
            CommandKind.StepCheckPhoto -> when (val r = apiCall { api.checkStep(c.sessionId, requireNotNull(c.stepId), c.id, part, c.source?.toRequestBody()) }) {
                is ApiOutcome.Ok -> ApiOutcome.Ok(null)
                is ApiOutcome.Rejected -> r
                is ApiOutcome.Unreachable -> r
            }
            else -> error("not a photo command: ${c.kind}")
        }
    }

    /** A queued JSON command: the body it was saved with, under its original key; the server's view replaces the cached one. */
    private suspend fun sendCommand(api: FieldOpsApi, c: PendingCommand, server: String): ApiOutcome<String?> {
        val id = c.sessionId
        // The saved body is read before the call, so an unreadable row is refused on the phone and never reaches the server.
        val body: Any? = try {
            when (c.kind) {
                CommandKind.RepairClaim -> json.decodeFromString(com.lensqi.fieldops.data.api.RepairClaimRequest.serializer(), requireNotNull(c.payload))
                CommandKind.Checklist -> json.decodeFromString(com.lensqi.fieldops.data.api.ChecklistRequest.serializer(), requireNotNull(c.payload))
                CommandKind.WorkOrder -> json.decodeFromString(com.lensqi.fieldops.data.api.WorkOrderRequest.serializer(), requireNotNull(c.payload))
                CommandKind.Acknowledge -> null
                else -> error("not a JSON command: ${c.kind}")
            }
        } catch (e: IllegalArgumentException) {
            return ApiOutcome.Rejected(0, "payload_unreadable", "The saved command could not be read; enter it again")
        }
        val r: ApiOutcome<com.lensqi.fieldops.data.api.InspectionView> = when (c.kind) {
            CommandKind.RepairClaim -> apiCall { api.repairClaim(id, c.id, body as com.lensqi.fieldops.data.api.RepairClaimRequest).state }
            CommandKind.Checklist -> apiCall { api.checklist(id, c.id, body as com.lensqi.fieldops.data.api.ChecklistRequest) }
            CommandKind.WorkOrder -> apiCall { api.workOrder(id, c.id, body as com.lensqi.fieldops.data.api.WorkOrderRequest) }
            CommandKind.Acknowledge -> apiCall { api.acknowledge(id, c.id) }
            else -> error("not a JSON command: ${c.kind}")
        }
        return when (r) {
            is ApiOutcome.Ok -> { cache.put(r.value, server); ApiOutcome.Ok(null) }
            is ApiOutcome.Rejected -> r
            is ApiOutcome.Unreachable -> r
        }
    }

    companion object {
        private const val WORK_NAME = "fieldops-sync"

        fun scheduleSync(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun mediaTypeOf(bytes: ByteArray): String? = when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
            else -> null
        }
    }
}
