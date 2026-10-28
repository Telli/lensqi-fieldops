package com.lensqi.fieldops.data

import com.lensqi.fieldops.data.api.KnowledgeResponse
import com.lensqi.fieldops.data.api.Recordings
import com.lensqi.fieldops.di.NetworkModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

class SnapshotStoreTest {
    private val json = NetworkModule.json()

    @Test
    fun knowledge_round_trips_with_its_fetch_time_and_pack_digest(@TempDir dir: File) {
        val store = SnapshotStore(dir, json)
        val k = json.decodeFromString(KnowledgeResponse.serializer(), Recordings.text("knowledge"))
        val at = Instant.parse("2026-10-28T05:00:00Z")
        store.save("knowledge-x", KnowledgeResponse.serializer(), k, at)

        val snap = store.load("knowledge-x", KnowledgeResponse.serializer())!!
        assertEquals(at.toString(), snap.fetchedAt)
        assertEquals(k.packSha256, snap.value.packSha256)
        assertEquals(k.facts.map { it.factId }, snap.value.facts.map { it.factId })
    }

    @Test
    fun missing_or_corrupt_snapshots_read_as_absent(@TempDir dir: File) {
        val store = SnapshotStore(dir, json)
        assertNull(store.load("nothing", KnowledgeResponse.serializer()))
        File(dir, "broken.json").writeText("{not json")
        assertNull(store.load("broken", KnowledgeResponse.serializer()))
    }
}
