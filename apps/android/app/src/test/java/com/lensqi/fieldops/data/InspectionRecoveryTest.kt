package com.lensqi.fieldops.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.lensqi.fieldops.data.api.*
import com.lensqi.fieldops.data.queue.*
import com.lensqi.fieldops.data.settings.SettingsStore
import com.lensqi.fieldops.di.NetworkModule
import com.lensqi.fieldops.ui.inspection.InspectionViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class InspectionRecoveryTest {
    private class Scene(val dispatcher: TestDispatcher, val models: ViewModelStore, val dao: MemoryCommands, val queue: CommandQueue,
        val server: String, val inspectionId: String, val requests: List<String>, val model: () -> InspectionViewModel)

    /** An inspection whose server accepts everything except analysis, which reports the provider unavailable. */
    private fun scene(dir: File, block: suspend Scene.() -> Unit) = runBlocking {
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val prefsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val models = ViewModelStore()
        try {
            MockWebServer().use { server ->
                val json = NetworkModule.json()
                val view = json.decodeFromString(InspectionView.serializer(), Recordings.text("inspection-created"))
                val requests = CopyOnWriteArrayList<String>()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        requests += request.path!!
                        return when {
                            request.path!!.contains("/investigate") -> MockResponse().setResponseCode(409).setBody("""{"code":"provider_unavailable","message":"retry analysis"}""")
                            request.path == "/health" -> MockResponse().setBody(Recordings.text("health"))
                            request.path == "/v1/fixtures" -> MockResponse().setBody(Recordings.text("fixtures"))
                            request.path == "/v1/inspections/${view.inspectionId}" -> MockResponse().setBody(Recordings.text("inspection-created"))
                            request.path!!.endsWith("/knowledge") -> MockResponse().setBody(Recordings.text("knowledge"))
                            else -> MockResponse().setBody("[]")
                        }
                    }
                }
                server.start()
                val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = prefsScope) { File(dir, "settings.preferences_pb") })
                settings.setApiBase(server.url("/").toString())
                val api = NetworkModule.retrofit(OkHttpClient(), json, server.url("/").toString()).create(FieldOpsApi::class.java)
                val dao = MemoryCommands(); val cache = InspectionCache()
                val queue = CommandQueue(File(dir, "captures"), dao, { api }, cache) {}
                Scene(dispatcher, models, dao, queue, server.url("/").toString(), view.inspectionId, requests) {
                    InspectionViewModel(SavedStateHandle(mapOf("inspectionId" to view.inspectionId)), api, cache, queue, settings, SnapshotStore(File(dir, "snapshots"), json), json)
                }.block()
            }
        } finally { models.clear(); prefsScope.cancel(); Dispatchers.resetMain() }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `an offline review survives sixty seconds and screen recreation then investigates exactly its uploaded photo`(restart: Boolean, @TempDir dir: File) = scene(dir) {
        val original = File(dir, "b.jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, 2)) }
        val a = queue.enqueuePhoto(inspectionId, original, "Phone", server)
        dao.uploaded(a.id, "older-A")
        val b = queue.enqueuePhoto(inspectionId, original, "Phone", server)
        var vm = model(); models.put("review", vm)
        withTimeout(5000) { vm.state.first { it.review?.awaitingUpload == true } }
        dispatcher.scheduler.advanceTimeBy(60_001)
        assertTrue(vm.state.value.review!!.awaitingUpload)
        if (restart) { models.clear(); vm = model(); models.put("review", vm) }
        withTimeout(5000) { vm.state.first { it.review?.awaitingUpload == true } }
        dao.uploaded(b.id, "exact-B")
        val resumed = withTimeout(5000) { vm.state.first { it.review?.done == true } }
        assertEquals("exact-B", resumed.review!!.photoId)
        assertFalse(resumed.review.awaitingUpload)
        val analyses = requests.filter { it.contains("/investigate") }
        assertEquals(1, analyses.size)
        assertTrue(analyses.single().contains("photoEvidenceId=exact-B"))
        // The older photo was already on the server and is not analysed, so its saved copy is gone.
        assertEquals(listOf(b.id), dao.rows.value.map { it.id })
    }

    @Test
    fun `a review closed while offline still analyses its photo once uploaded and then removes the saved copy`(@TempDir dir: File) = scene(dir) {
        val photo = File(dir, "c.jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, 3)) }
        val vm = model(); models.put("review", vm)
        val c = queue.enqueuePhoto(inspectionId, photo, "Phone", server)
        withTimeout(5000) { vm.state.first { it.review?.awaitingUpload == true } }
        vm.closeReview()
        withTimeout(5000) { vm.state.first { it.review == null } }
        dao.uploaded(c.id, "closed-C")
        withTimeout(5000) { dao.rows.first { it.isEmpty() } }
        assertTrue(requests.single { it.contains("/investigate") }.contains("photoEvidenceId=closed-C"))
        assertFalse(File(c.filePath!!).exists())
        assertNull(withTimeout(5000) { vm.state.first() }.review)
    }
}
