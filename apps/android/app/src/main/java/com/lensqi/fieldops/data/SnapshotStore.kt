package com.lensqi.fieldops.data

import com.lensqi.fieldops.data.api.InspectionView
import com.lensqi.fieldops.data.api.closed
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/** A server response kept on the device, with the time it was fetched. Shown offline only with a Cached label. */
@Serializable
data class Snapshot<T>(val fetchedAt: String, val value: T)

/**
 * Last-known server responses (inspection view, vendor knowledge) for offline display. A snapshot is never sent back
 * to the server and never stands in for a decision: commands still need the API.
 */
class SnapshotStore(private val dir: File, private val json: Json) {
    companion object {
        /** Never reuse pre-isolation snapshots: their server and inspection ownership cannot be trusted. */
        fun key(server: String, kind: String, id: String = ""): String =
            java.security.MessageDigest.getInstance("SHA-256").digest("$server|$kind|$id".toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
    @Synchronized
    fun <T> save(key: String, serializer: KSerializer<T>, value: T, now: Instant = Instant.now()) {
        dir.mkdirs()
        val tmp = File.createTempFile("snapshot-$key-", ".tmp", dir)
        try {
            tmp.writeText(json.encodeToString(Snapshot.serializer(serializer), Snapshot(now.toString(), value)))
            if (!tmp.renameTo(File(dir, "$key.json"))) throw java.io.IOException("Could not replace snapshot")
        } finally {
            tmp.delete()
        }
    }

    /** A terminal summary without detail is stored as a null view. Old readers see no usable detail. */
    suspend fun reconcileView(key: String, incoming: InspectionView?): InspectionView? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            synchronized(this@SnapshotStore) {
                val serializer = InspectionView.serializer().nullable
                val saved = load(key, serializer)
                val old = saved?.value
                when {
                    incoming == null && old?.stage?.closed == true -> old
                    incoming != null && old != null && old.lastSequence >= incoming.lastSequence -> old
                    incoming != null && !incoming.stage.closed && saved != null && (old == null || old.stage.closed) -> old
                    else -> { save(key, serializer, incoming); incoming }
                }
            }
        }

    /** The same save and load off the main thread, for callers that are already in a coroutine. */
    suspend fun <T> saveOn(key: String, serializer: KSerializer<T>, value: T) =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { save(key, serializer, value) }
    suspend fun <T> loadOn(key: String, serializer: KSerializer<T>): Snapshot<T>? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { load(key, serializer) }

    @Synchronized
    fun <T> load(key: String, serializer: KSerializer<T>): Snapshot<T>? {
        val file = File(dir, "$key.json")
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(Snapshot.serializer(serializer), file.readText()) }.getOrNull()
    }
}
