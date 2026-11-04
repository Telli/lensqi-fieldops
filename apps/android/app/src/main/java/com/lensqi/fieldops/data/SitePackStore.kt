package com.lensqi.fieldops.data

import com.lensqi.fieldops.data.api.Fixture
import com.lensqi.fieldops.data.api.InspectionSummary
import com.lensqi.fieldops.data.api.closed
import com.lensqi.fieldops.data.api.SitePack
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** What a site pack left on this phone: enough to say when it was saved and what it covered. Photos are never part of a pack. */
@Serializable
data class SitePackReceipt(val fixtureId: String, val fixtureRevision: Int, val sha256: String, val generatedAt: String, val assets: Int, val open: Int, val savedAt: String)

/**
 * Pre-positions one fixture's site pack under the same snapshot keys the screens already read offline: the asset's
 * documentation (read by the inspection screen when there is no inspection-keyed copy), each open inspection's view, the
 * inspection list and the fixtures list the home tabs show. Everything saved is a server response; nothing is derived.
 */
@Singleton
class SitePackStore @Inject constructor(private val snapshots: SnapshotStore, private val cache: InspectionCache) {
    suspend fun store(pack: SitePack, server: String, now: Instant = Instant.now()) {
        for (asset in pack.assets)
            snapshots.saveOn(knowledgeKey(server, pack.fixtureId, asset.assetId), com.lensqi.fieldops.data.api.KnowledgeResponse.serializer(), asset.knowledge)
        for (view in pack.open) {
            val current = cache.observe(view.inspectionId, server).first()
            val candidate = if (cache.hasTerminalSummary(view.inspectionId, server)) null
                else listOfNotNull(view, current).maxBy { it.lastSequence }
            val newest = snapshots.reconcileView(inspectionKey(server, view.inspectionId), candidate)
            if (newest != null) cache.put(newest, server) else cache.markClosed(view.inspectionId, server)
        }
        for (row in pack.inspections.filter { it.stage.closed }) {
            // A closed detail already on this phone is kept and stays readable; only an obsolete open one is invalidated.
            val kept = snapshots.reconcileView(inspectionKey(server, row.inspectionId), null)
            if (kept != null) cache.put(kept, server) else cache.markClosed(row.inspectionId, server)
        }
        // This fixture's rows come from the pack; other fixtures keep whatever the list last showed.
        val listSerializer = ListSerializer(InspectionSummary.serializer())
        val others = snapshots.loadOn(listKey(server), listSerializer)?.value.orEmpty().filter { it.fixtureId != pack.fixtureId }
        snapshots.saveOn(listKey(server), listSerializer, (others + pack.inspections).sortedByDescending { it.updatedAt })
        val fixturesSerializer = ListSerializer(Fixture.serializer())
        val fixtures = snapshots.loadOn(fixturesKey(server), fixturesSerializer)?.value.orEmpty()
        val replaced = if (fixtures.any { it.fixtureId == pack.fixtureId }) fixtures.map { if (it.fixtureId == pack.fixtureId) pack.fixture else it } else fixtures + pack.fixture
        snapshots.saveOn(fixturesKey(server), fixturesSerializer, replaced)
        snapshots.saveOn(receiptKey(server, pack.fixtureId), SitePackReceipt.serializer(),
            SitePackReceipt(pack.fixtureId, pack.fixtureRevision, pack.sha256, pack.generatedAt, pack.assets.size, pack.open.size, now.toString()))
    }

    suspend fun receipt(fixtureId: String, server: String): Snapshot<SitePackReceipt>? = snapshots.loadOn(receiptKey(server, fixtureId), SitePackReceipt.serializer())

    companion object {
        /** An asset's documentation as the pack carried it, read when the inspection has no copy of its own. */
        fun knowledgeKey(server: String, fixtureId: String, assetId: String) = SnapshotStore.key(server, "knowledge-asset", "$fixtureId/$assetId")
        fun inspectionKey(server: String, inspectionId: String) = SnapshotStore.key(server, "inspection", inspectionId)
        fun listKey(server: String) = SnapshotStore.key(server, "inspections-list")
        fun fixturesKey(server: String) = SnapshotStore.key(server, "fixtures")
        fun receiptKey(server: String, fixtureId: String) = SnapshotStore.key(server, "site-pack", fixtureId)
    }
}
