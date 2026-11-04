package com.lensqi.fieldops.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import androidx.lifecycle.ViewModelStore
import com.lensqi.fieldops.data.api.FieldOpsApi
import com.lensqi.fieldops.data.api.Fixture
import com.lensqi.fieldops.data.api.InspectionView
import com.lensqi.fieldops.data.api.KnowledgeResponse
import com.lensqi.fieldops.data.api.GroundedProposal
import com.lensqi.fieldops.data.api.ProposedNextAction
import com.lensqi.fieldops.data.api.RegisteredPrediction
import com.lensqi.fieldops.data.api.ProposalPrediction
import com.lensqi.fieldops.data.api.ReasonResponse
import com.lensqi.fieldops.data.api.DiagnosticSource
import com.lensqi.fieldops.data.api.PredictionRow
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

    private fun scene(
        dir: File,
        seedPack: Boolean,
        offline: Boolean = false,
        staleStatus: String? = null,
        disconnected: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(false),
        block: suspend (InspectionViewModel) -> Unit,
    ) = runBlocking {
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
                        request.path!!.endsWith("/predictions") -> MockResponse().setBody("""{"inspectionId":"${view.inspectionId}","predictions":[],"tally":{"confirmed":0,"falsified":0,"unscorable":0,"pending":0}}""")
                        else -> MockResponse().setBody("[]")
                    }
                }
                server.start()
                val base = server.url("/").toString()
                val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = prefsScope) { File(dir, "settings.preferences_pb") })
                settings.setApiBase(base)
                // The knowledge endpoint alone is out of reach: the connection fails before any request is sent.
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    if (offline || disconnected.get() || chain.request().url.encodedPath.endsWith("/knowledge")) throw java.io.IOException("simulated: no route to host") else chain.proceed(chain.request())
                }.build()
                val api = NetworkModule.retrofit(client, json, base).create(FieldOpsApi::class.java)
                val cache = InspectionCache()
                val snapshots = SnapshotStore(File(dir, "snapshots"), json)
                if (staleStatus != null) snapshots.save(SnapshotStore.key(base, "knowledge", view.inspectionId), KnowledgeResponse.serializer(),
                    knowledge.copy(inspectionId = view.inspectionId, status = staleStatus, packSha256 = "old"))
                val savedView = if (offline) json.decodeFromString(InspectionView.serializer(),
                    Recordings.text("checklist-1-switch_chassis_visible")) else view
                if (seedPack) SitePackStore(snapshots, cache).store(
                    SitePack(fixture.fixtureId, fixture.fixtureRevision, fixture.sha256, "2026-11-03T09:00:00+00:00", fixture, listOf(SitePackAsset(view.assetId, knowledge)), emptyList(), listOf(savedView)), base)
                val reopenedCache = InspectionCache()
                val queue = CommandQueue(File(dir, "captures"), MemoryCommands(), { api }, reopenedCache) {}
                val vm = InspectionViewModel(SavedStateHandle(mapOf("inspectionId" to view.inspectionId)), api, reopenedCache, queue, settings, snapshots, json)
                models.put("vm", vm)
                try {
                    block(vm)
                } finally {
                    vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
                }
            }
        } finally {
            models.clear()
            prefsScope.coroutineContext[Job]?.cancelAndJoin()
            Dispatchers.resetMain()
        }
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

    @Test fun `cold offline restart restores the mandatory checklist from the matching fixture`(@TempDir dir: File) =
        scene(dir, seedPack = true, offline = true) { vm ->
            val ui = withTimeout(5000) { vm.state.first { it.knowledge != null } }
            assertEquals(fixture.visualRequirements, ui.fixture?.visualRequirements)
            assertEquals(view.fixtureDigest, ui.fixture?.sha256)
            assertEquals(com.lensqi.fieldops.data.api.InspectionStage.ReadyToVerify, ui.view?.stage)
            assertNotNull(ui.view?.acceptedPostRepairPhotoId)
        }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["NoPack", "Applicable"])
    fun `new site documentation supersedes stale inspection knowledge`(status: String, @TempDir dir: File) =
        scene(dir, seedPack = true, offline = true, staleStatus = status) { vm ->
            val ui = withTimeout(5000) { vm.state.first { it.knowledge != null } }
            assertEquals(knowledge.packSha256, ui.knowledge?.packSha256)
            assertNotNull(ui.knowledgeCachedAt)
        }

    @Test fun `predictions become dated when the same screen loses its connection`(@TempDir dir: File) {
        val disconnected = java.util.concurrent.atomic.AtomicBoolean(false)
        scene(dir, seedPack = true, disconnected = disconnected) { vm ->
            val online = withTimeout(5000) { vm.state.first { it.predictions != null } }
            assertNull(online.predictionsCachedAt)
            disconnected.set(true)
            vm.refresh().join()
            val saved = withTimeout(5000) { vm.state.first { it.predictionsCachedAt != null } }
            assertEquals(online.predictions, saved.predictions)
        }
    }

    @Test fun `only an accepted probe with a registered prediction offers a check`() {
        val proposed = GroundedProposal(view.assetId, proposedDiagnosticId = "check", nextAction = ProposedNextAction.RequestHumanReview,
            prediction = ProposalPrediction("check", "Up"))
        val response = ReasonResponse(true, "p", "model", proposal = proposed, state = view)
        assertNull(response.probePrediction)
        val registered = RegisteredPrediction("id", "check", "Up")
        assertNull(response.copy(prediction = registered).probePrediction)
        val probe = response.copy(proposal = proposed.copy(nextAction = ProposedNextAction.RunApprovedProbe))
        assertNull(probe.probePrediction)
        assertEquals(registered, probe.copy(prediction = registered).probePrediction)
    }

    @Test fun `scored predictions use their own diagnostic evidence source after serialization`() {
        val detail = json.decodeFromString(InspectionView.serializer(), Recordings.text("inspection"))
        val diagnostic = detail.diagnostics.first()
        val row = PredictionRow("p", diagnostic.result.diagnosticId, "Up", registeredAt = "now", outcome = "Confirmed", diagnosticEvidenceId = diagnostic.evidenceId)
        assertEquals(DiagnosticSource.Simulated, row.sourceIn(detail))
        assertNull(row.copy(diagnosticEvidenceId = "other").sourceIn(detail))
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

    @Test fun `a prediction is asked only from the claim the server recorded`() {
        assertEquals("Technician's repair claim: Swapped the cable. Predict what the approved equipment check will report.", InspectionViewModel.predictionQuestion("Swapped the cable"))
        // The claim field's sample line must not stand in while the events load or when they fail: it would be recorded as the technician's words.
        assertNull(InspectionViewModel.predictionQuestion(null))
        assertNull(InspectionViewModel.predictionQuestion("  "))
    }
}
