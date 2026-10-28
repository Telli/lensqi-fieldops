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

/**
 * Offline-first command queue (Track B day 2). Captures land in Room first, then drain in order. A 4xx keeps the
 * command visible with the server's code and moves on; a network failure stops the drain and WorkManager retries.
 */
@Singleton
class CommandQueue @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: PendingCommandDao,
    private val api: FieldOpsApi,
    private val cache: InspectionCache,
) {
    private val mutex = Mutex()

    fun observe(inspectionId: String): Flow<List<PendingCommand>> = dao.observe(inspectionId)

    fun requestSync() = scheduleSync(context)

    /** Moves a captured or imported file into app storage, hashes it on device and queues the upload. */
    suspend fun enqueuePhoto(inspectionId: String, source: File, origin: String, observedAt: Instant = Instant.now()): PendingCommand {
        val id = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "captures").apply { mkdirs() }
        val bytes = source.readBytes()
        val mediaType = mediaTypeOf(bytes) ?: error("Only JPEG or PNG images can be evidence")
        val target = File(dir, "$id.${if (mediaType == "image/png") "png" else "jpg"}").apply { writeBytes(bytes) }
        if (source.parentFile == context.cacheDir) source.delete()
        val command = PendingCommand(
            id = id, inspectionId = inspectionId, kind = CommandKind.UploadPhoto, createdAtMillis = System.currentTimeMillis(),
            filePath = target.absolutePath, mediaType = mediaType, sha256 = sha256(bytes), source = origin, observedAt = observedAt.toString(),
        )
        dao.insert(command)
        scheduleSync(context)
        return command
    }

    suspend fun drain(): DrainResult = mutex.withLock {
        var next = dao.nextPending()
        while (next != null) {
            dao.countAttempt(next.id)
            when (val outcome = send(next)) {
                is ApiOutcome.Ok -> {
                    dao.delete(next.id)
                    next.filePath?.let { File(it).delete() }
                }
                is ApiOutcome.Rejected -> dao.reject(next.id, outcome.code, outcome.message)
                is ApiOutcome.Unreachable -> return@withLock DrainResult.Offline
            }
            next = dao.nextPending()
        }
        DrainResult.Empty
    }

    private suspend fun send(c: PendingCommand): ApiOutcome<Unit> = when (c.kind) {
        CommandKind.UploadPhoto -> {
            val file = File(requireNotNull(c.filePath))
            val part = MultipartBody.Part.createFormData("image", file.name, file.asRequestBody(requireNotNull(c.mediaType).toMediaType()))
            when (val r = apiCall { api.uploadObservation(c.inspectionId, c.id, part, requireNotNull(c.source).toRequestBody(), requireNotNull(c.observedAt).toRequestBody()) }) {
                is ApiOutcome.Ok -> {
                    cache.put(r.value.state)
                    // The server hashes what it stored; a different digest means the bytes changed in transit.
                    if (r.value.sha256 != c.sha256) ApiOutcome.Rejected(0, "client_digest_mismatch", "device ${c.sha256?.take(12)} vs server ${r.value.sha256.take(12)}")
                    else ApiOutcome.Ok(Unit)
                }
                is ApiOutcome.Rejected -> r
                is ApiOutcome.Unreachable -> r
            }
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
