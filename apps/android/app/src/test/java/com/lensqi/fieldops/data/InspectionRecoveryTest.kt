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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `an offline review survives sixty seconds and screen recreation then investigates exactly its uploaded photo`(restart: Boolean, @TempDir dir: File) = runBlocking {
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
                val original = File(dir, "b.jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, 2)) }
                val a = queue.enqueuePhoto(view.inspectionId, original, "Phone", server.url("/").toString())
                dao.uploaded(a.id, "older-A")
                val b = queue.enqueuePhoto(view.inspectionId, original, "Phone", server.url("/").toString())
                fun model() = InspectionViewModel(SavedStateHandle(mapOf("inspectionId" to view.inspectionId)), api, cache, queue, settings, SnapshotStore(File(dir, "snapshots"), json), json)
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
            }
        } finally { models.clear(); prefsScope.cancel(); Dispatchers.resetMain() }
    }
}
