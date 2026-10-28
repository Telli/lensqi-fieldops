package com.lensqi.fieldops.data

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
    fun <T> save(key: String, serializer: KSerializer<T>, value: T, now: Instant = Instant.now()) {
        dir.mkdirs()
        val tmp = File(dir, "$key.json.tmp")
        tmp.writeText(json.encodeToString(Snapshot.serializer(serializer), Snapshot(now.toString(), value)))
        tmp.renameTo(File(dir, "$key.json"))
    }

    fun <T> load(key: String, serializer: KSerializer<T>): Snapshot<T>? {
        val file = File(dir, "$key.json")
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(Snapshot.serializer(serializer), file.readText()) }.getOrNull()
    }
}
