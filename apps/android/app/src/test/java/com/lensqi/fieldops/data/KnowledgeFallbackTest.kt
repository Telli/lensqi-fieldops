package com.lensqi.fieldops.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.lensqi.fieldops.data.api.FieldOpsApi
import com.lensqi.fieldops.data.api.Fixture
import com.lensqi.fieldops.data.api.InspectionView
import com.lensqi.fieldops.data.api.KnowledgeResponse
import com.lensqi.fieldops.data.api.Recordings
import com.lensqi.fieldops.data.api.SitePack
import com.lensqi.fieldops.data.api.SitePackAsset
import com.lensqi.fieldops.data.queue.CommandQueue
import com.lensqi.fieldops.data.settings.SettingsStore
import com.lensqi.fieldops.di.NetworkModule
import com.lensqi.fieldops.ui.inspection.InspectionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * With the knowledge endpoint unreachable and no copy of its own, an inspection shows the documentation a site pack left
 * on the phone for its asset, marked as the saved copy. Without a pack it shows nothing rather than guessing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeFallbackTest {
    private val json = NetworkModule.json()
    private val view = json.decodeFromString(InspectionView.serializer(), Recordings.text("inspection-created"))
    private val fixture = json.decodeFromString(ListSerializer(Fixture.serializer()), Recordings.text("fixtures")).first { it.fixtureId == view.fixtureId }
    private val knowledge = json.decodeFromString(KnowledgeResponse.serializer(), Recordings.text("knowledge")).copy(inspectionId = null)

    private fun scene(dir: File, seedPack: Boolean, block: suspend (InspectionViewModel) -> Unit) = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val prefsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val models = ViewModelStore()
        try {
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when {
                        request.path == "/health" -> MockResponse().setBody(Recordings.text("health"))
                        request.path == "/v1/fixtures" -> MockResponse().setBody(Recordings.text("fixtures"))
                        request.path == "/v1/inspections/${view.inspectionId}" -> MockResponse().setBody(Recordings.text("inspection-created"))
                        request.path!!.endsWith("/predictions") -> MockResponse().setResponseCode(404).setBody("""{"code":"not_found","message":"older server"}""")
                        else -> MockResponse().setBody("[]")
                    }
                }
                server.start()
                val base = server.url("/").toString()
                val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = prefsScope) { File(dir, "settings.preferences_pb") })
                settings.setApiBase(base)
                // The knowledge endpoint alone is out of reach: the connection fails before any request is sent.
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    if (chain.request().url.encodedPath.endsWith("/knowledge")) throw java.io.IOException("simulated: no route to host") else chain.proceed(chain.request())
                }.build()
                val api = NetworkModule.retrofit(client, json, base).create(FieldOpsApi::class.java)
                val cache = InspectionCache()
                val snapshots = SnapshotStore(File(dir, "snapshots"), json)
                if (seedPack) SitePackStore(snapshots, cache).store(
                    SitePack(fixture.fixtureId, fixture.fixtureRevision, fixture.sha256, "2026-11-03T09:00:00+00:00", fixture, listOf(SitePackAsset(view.assetId, knowledge)), emptyList(), emptyList()), base)
                val queue = CommandQueue(File(dir, "captures"), MemoryCommands(), { api }, cache) {}
                val vm = InspectionViewModel(SavedStateHandle(mapOf("inspectionId" to view.inspectionId)), api, cache, queue, settings, snapshots, json)
                models.put("vm", vm)
                block(vm)
            }
        } finally { models.clear(); prefsScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun `offline knowledge falls back to the asset's site-pack copy, marked cached`(@TempDir dir: File) = scene(dir, seedPack = true) { vm ->
        val s = withTimeout(5000) { vm.state.first { it.knowledge != null } }
        assertEquals(knowledge.packSha256, s.knowledge!!.packSha256)
        assertNotNull(s.knowledgeCachedAt, "a copy from the pack is always marked as saved on the phone")
        assertEquals(view.inspectionId, s.view?.inspectionId)
    }

    @Test fun `without a pack the inspection shows no documentation rather than another asset's`(@TempDir dir: File) = scene(dir, seedPack = false) { vm ->
        val s = withTimeout(5000) { vm.state.first { it.view != null && it.fixture != null } }
        assertNull(s.knowledge)
        assertNull(s.knowledgeCachedAt)
    }

    @Test fun `the newest proposal since the claim decides whether the refusal line shows`() {
        fun event(kind: String, at: String, codes: List<String> = emptyList()) = buildJsonObject {
            put("kind", kind); put("occurredAt", at)
            if (codes.isNotEmpty()) put("rejections", buildJsonArray { codes.forEach { add(buildJsonObject { put("code", it); put("message", "m") }) } })
        }
        val claim = "2026-11-03T10:00:00+00:00"
        val before = event("KnowledgeProposalRejected", "2026-11-03T09:00:00+00:00", listOf("unknown_fact"))
        val rejected = event("KnowledgeProposalRejected", "2026-11-03T10:05:00+00:00", listOf("unknown_fact", "diagnostic_not_approved"))
        val accepted = event("KnowledgeProposalAccepted", "2026-11-03T10:10:00+00:00")
        assertEquals(listOf("unknown_fact", "diagnostic_not_approved"), InspectionViewModel.refusedSinceClaim(listOf(before, rejected), claim))
        assertNull(InspectionViewModel.refusedSinceClaim(listOf(before, rejected, accepted), claim), "an accepted proposal after the refusal clears it")
        assertNull(InspectionViewModel.refusedSinceClaim(listOf(before), claim), "a refusal before the claim is about older evidence")
        assertNull(InspectionViewModel.refusedSinceClaim(listOf(rejected), null), "no claim, no card")
    }
}
