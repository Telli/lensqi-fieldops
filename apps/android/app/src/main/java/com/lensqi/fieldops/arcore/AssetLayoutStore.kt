package com.lensqi.fieldops.arcore

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Port layouts marked on this phone, one JSON file per fixture and asset under [dir]. They stay on the device: the
 * server has no layout endpoint, and a layout is never evidence. A file that does not parse is treated as absent.
 */
class AssetLayoutStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private fun file(fixtureId: String, assetId: String) = File(dir, "${safe(fixtureId)}__${safe(assetId)}.json")

    fun load(fixtureId: String, assetId: String): AssetLayout? =
        runCatching { json.decodeFromString(AssetLayout.serializer(), file(fixtureId, assetId).readText()) }.getOrNull()

    fun save(layout: AssetLayout) {
        dir.mkdirs()
        val target = file(layout.fixtureId, layout.assetId)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(json.encodeToString(AssetLayout.serializer(), layout))
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }

    fun delete(fixtureId: String, assetId: String) { file(fixtureId, assetId).delete() }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
