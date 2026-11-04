package com.lensqi.fieldops.data

import com.lensqi.fieldops.data.api.Fixture
import com.lensqi.fieldops.data.api.InspectionSummary
import com.lensqi.fieldops.data.api.InspectionView
import com.lensqi.fieldops.data.api.KnowledgeResponse
import com.lensqi.fieldops.data.api.Recordings
import com.lensqi.fieldops.data.api.SitePack
import com.lensqi.fieldops.data.api.SitePackAsset
import com.lensqi.fieldops.di.NetworkModule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

/** A site pack lands under the snapshot keys the screens already read offline, and never disturbs another fixture's rows. */
class SitePackStoreTest {
    private val json = NetworkModule.json()
    private val server = "http://10.0.2.2:5201/"
    private val fixture = json.decodeFromString(ListSerializer(Fixture.serializer()), Recordings.text("fixtures")).first()
    private val knowledge = json.decodeFromString(KnowledgeResponse.serializer(), Recordings.text("knowledge")).copy(inspectionId = null)
    private val open = json.decodeFromString(InspectionView.serializer(), Recordings.text("inspection-created"))
    private val rows = json.decodeFromString(ListSerializer(InspectionSummary.serializer()), Recordings.text("inspections")).map { it.copy(stage = open.stage) }

    private fun pack() = SitePack(fixture.fixtureId, fixture.fixtureRevision, fixture.sha256, "2026-11-03T09:00:00+00:00", fixture,
        listOf(SitePackAsset(open.assetId, knowledge)), rows, listOf(open))

    @Test fun `an older pack cannot roll back a detail or reopen a terminal inspection`(@TempDir dir: File) = runBlocking {
        val snapshots = SnapshotStore(dir, json)
        val cache = InspectionCache()
        val store = SitePackStore(snapshots, cache)
        val key = SitePackStore.inspectionKey(server, open.inspectionId)
        val newer = open.copy(lastSequence = open.lastSequence + 1)
        snapshots.reconcileView(key, newer)
        store.store(pack().copy(inspections = emptyList()), server)
        assertEquals(newer, snapshots.load(key, InspectionView.serializer())?.value)
        assertEquals(newer, cache.observe(open.inspectionId, server).first())

        val closed = rows.first().copy(inspectionId = open.inspectionId, stage = com.lensqi.fieldops.data.api.InspectionStage.Inconclusive)
        store.store(pack().copy(inspections = listOf(closed), open = emptyList()), server)
        assertNull(snapshots.reconcileView(key, open))
        cache.put(open, server)
        assertNull(cache.observe(open.inspectionId, server).first())
        assertNull(SnapshotStore(dir, json).load(key, InspectionView.serializer()))
    }

    @Test fun `newer in-memory views are persisted when a delayed pack arrives`(@TempDir dir: File) = runBlocking {
        val cache = InspectionCache()
        val newest = open.copy(lastSequence = open.lastSequence + 5)
        cache.put(newest, server)
        SitePackStore(SnapshotStore(dir, json), cache).store(pack(), server)
        assertEquals(newest, SnapshotStore(dir, json).load(SitePackStore.inspectionKey(server, open.inspectionId), InspectionView.serializer())?.value)
    }

    @Test fun `storing a pack writes the asset knowledge, the open view, the merged lists and a receipt`(@TempDir dir: File) = runBlocking {
        val snapshots = SnapshotStore(dir, json)
        val cache = InspectionCache()
        // Another fixture's row and an older row of this fixture are already on the phone.
        val foreign = rows.first().copy(inspectionId = "I-other", fixtureId = "OTHER-RACK", assetId = "OTHER-SW", updatedAt = "2026-10-01T00:00:00+00:00")
        val stale = rows.first().copy(inspectionId = "I-stale", updatedAt = "2026-09-01T00:00:00+00:00")
        snapshots.save(SitePackStore.listKey(server), ListSerializer(InspectionSummary.serializer()), listOf(foreign, stale))
        snapshots.save(SitePackStore.fixturesKey(server), ListSerializer(Fixture.serializer()), listOf(fixture.copy(fixtureRevision = 0)))

        val at = Instant.parse("2026-11-03T10:00:00Z")
        SitePackStore(snapshots, cache).store(pack(), server, at)

        val k = snapshots.load(SitePackStore.knowledgeKey(server, fixture.fixtureId, open.assetId), KnowledgeResponse.serializer())
        assertNotNull(k, "the asset's documentation is saved under the fixture/asset key")
        assertEquals(knowledge.packSha256, k!!.value.packSha256)
        assertNull(k.value.inspectionId, "a pack's knowledge belongs to the asset, not to one inspection")

        val v = snapshots.load(SitePackStore.inspectionKey(server, open.inspectionId), InspectionView.serializer())
        assertEquals(open.lastSequence, v!!.value.lastSequence, "the open view is saved under the key the inspection screen reads offline")
        assertEquals(open.inspectionId, cache.observe(open.inspectionId, server).first()?.inspectionId, "and put in the in-memory cache")

        val list = snapshots.load(SitePackStore.listKey(server), ListSerializer(InspectionSummary.serializer()))!!.value
        assertTrue(list.any { it.inspectionId == "I-other" }, "another fixture's rows are kept")
        assertTrue(list.none { it.inspectionId == "I-stale" }, "this fixture's old rows are replaced by the pack's")
        assertEquals(rows.map { it.inspectionId }, list.filter { it.fixtureId == fixture.fixtureId }.map { it.inspectionId })

        val fixtures = snapshots.load(SitePackStore.fixturesKey(server), ListSerializer(Fixture.serializer()))!!.value
        assertEquals(1, fixtures.size)
        assertEquals(fixture.fixtureRevision, fixtures.single().fixtureRevision, "the fixture is replaced, not duplicated")

        val receipt = SitePackStore(snapshots, cache).receipt(fixture.fixtureId, server)!!.value
        assertEquals(SitePackReceipt(fixture.fixtureId, fixture.fixtureRevision, fixture.sha256, "2026-11-03T09:00:00+00:00", 1, 1, at.toString()), receipt)
    }

    @Test fun `a pack for one server never shows up under another`(@TempDir dir: File) = runBlocking {
        val snapshots = SnapshotStore(dir, json)
        val store = SitePackStore(snapshots, InspectionCache())
        store.store(pack(), server)
        assertNull(store.receipt(fixture.fixtureId, "http://other.example/"))
        assertNull(snapshots.load(SitePackStore.knowledgeKey("http://other.example/", fixture.fixtureId, open.assetId), KnowledgeResponse.serializer()))
    }
}
