package com.lensqi.fieldops.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import com.lensqi.fieldops.data.InspectionCache
import com.lensqi.fieldops.data.SitePackStore
import com.lensqi.fieldops.data.SnapshotStore
import com.lensqi.fieldops.data.api.FieldOpsApi
import com.lensqi.fieldops.data.api.Recordings
import com.lensqi.fieldops.data.settings.SettingsStore
import com.lensqi.fieldops.di.NetworkModule
import com.lensqi.fieldops.ui.home.HomeState
import com.lensqi.fieldops.ui.home.HomeViewModel
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** The home tabs against a scripted server: a list call that fails never empties what the technician already sees. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val models = mutableListOf<HomeViewModel>()
    private lateinit var server: MockWebServer
    private val fixturesDown = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/health" -> MockResponse().setBody(Recordings.text("health"))
                    "/v1/fixtures" -> if (fixturesDown.get()) MockResponse().setResponseCode(500).setBody("""{"code":"internal","message":"Unexpected server error"}""")
                        else MockResponse().setBody(Recordings.text("fixtures"))
                    "/v1/inspections" -> MockResponse().setBody(Recordings.text("inspections"))
                    "/v1/fixtures/LAB-RACK-01/pack" -> MockResponse().setBody(sitePackJson())
                    else -> MockResponse().setResponseCode(404).setBody("""{"code":"not_found","message":"no"}""")
                }
            }
            start()
        }
    }

    @AfterEach
    fun tearDown() = runBlocking {
        models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        server.shutdown()
        scope.coroutineContext[Job]?.cancelAndJoin()
        Dispatchers.resetMain()
    }

    /** One preferences file per test (DataStore allows one open store per file); each view model is a fresh "app start". */
    private fun settings(dir: File) = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") })

    private fun viewModel(dir: File, settings: SettingsStore): HomeViewModel {
        val json = NetworkModule.json()
        val api = NetworkModule.retrofit(OkHttpClient.Builder().addInterceptor(NetworkModule.baseUrlInterceptor { server.url("/") }).build(), json).create(FieldOpsApi::class.java)
        val snapshots = SnapshotStore(File(dir, "snapshots"), json)
        val cache = InspectionCache()
        return HomeViewModel(api, settings, cache, snapshots, SitePackStore(snapshots, cache)).also(models::add)
    }

    @Test fun `downloading a site pack leaves a dated receipt in the state and on disk`(@TempDir dir: File) {
        val settings = settings(dir)
        val vm = viewModel(dir, settings)
        await(vm) { it.reachable == true && it.fixtures.isNotEmpty() && !it.loading }
        assertTrue(vm.state.value.sitePacks.isEmpty(), "nothing saved yet")

        vm.downloadSitePack("LAB-RACK-01")
        val saved = await(vm) { it.downloadingPack == null && it.sitePacks.containsKey("LAB-RACK-01") }
        val receipt = saved.sitePacks.getValue("LAB-RACK-01")
        assertEquals(1, receipt.fixtureRevision)
        assertEquals(1, receipt.assets)
        assertEquals(1, receipt.open)
        assertTrue(receipt.savedAt.isNotBlank())
        assertTrue(saved.packNotice!!.contains("Photos are not included"), saved.packNotice)

        // A fresh app start reads the receipt back for the known fixtures.
        val restarted = await(viewModel(dir, settings)) { !it.loading && it.sitePacks.containsKey("LAB-RACK-01") }
        assertEquals(receipt, restarted.sitePacks["LAB-RACK-01"])
    }

    private fun await(vm: HomeViewModel, what: (HomeState) -> Boolean): HomeState = runBlocking { withTimeout(5000) { vm.state.first(what) } }

    @Test fun `an unwritable pack fails visibly and can be retried`(@TempDir dir: File) {
        val vm = viewModel(dir, settings(dir))
        await(vm) { !it.loading && it.fixtures.isNotEmpty() }
        val folder = File(dir, "snapshots")
        val backup = File(dir, "saved-snapshots")
        assertTrue(folder.renameTo(backup))
        folder.writeText("not a directory")
        vm.downloadSitePack("LAB-RACK-01")
        val failed = await(vm) { it.packError != null && it.downloadingPack == null }
        assertTrue(failed.sitePacks.isEmpty())
        assertTrue(folder.delete() && backup.renameTo(folder))
        vm.downloadSitePack("LAB-RACK-01")
        await(vm) { it.sitePacks.isNotEmpty() && it.downloadingPack == null }
    }

    @Test fun `a failed fixtures call keeps the assets already shown`(@TempDir dir: File) {
        val vm = viewModel(dir, settings(dir))
        val loaded = await(vm) { it.reachable == true && it.fixtures.isNotEmpty() && !it.loading }
        val assets = loaded.fixtures.flatMap { it.assets }.map { it.assetId }
        assertTrue(assets.isNotEmpty())

        fixturesDown.set(true)
        vm.refresh()
        val after = await(vm) { !it.loading && it.fixturesCachedAt != null }
        assertEquals(true, after.reachable)
        assertEquals(assets, after.fixtures.flatMap { it.assets }.map { it.assetId }, "the server answered health but not fixtures: the list stays")
        assertTrue(after.fixturesCachedAt!!.isNotBlank(), "and it is marked as the saved copy, with when it was fetched")
        assertTrue(after.fixturesFailed && !loaded.fixturesFailed)
        assertEquals(null, loaded.fixturesCachedAt, "a fresh answer carries no cached mark")
    }

    @Test fun `a cold start whose fixtures call fails shows the device copy`(@TempDir dir: File) {
        val settings = settings(dir)
        await(viewModel(dir, settings)) { it.fixtures.isNotEmpty() && !it.loading } // saves the snapshot
        fixturesDown.set(true)
        val restarted = await(viewModel(dir, settings)) { !it.loading && it.reachable == true }
        assertTrue(restarted.fixtures.isNotEmpty(), "the snapshot saved by the earlier run fills the list")
        assertTrue(restarted.fixturesCachedAt != null, "and says it is the saved copy")
    }
}

/** GET /v1/fixtures/{id}/pack as the backend shapes it, assembled from the recorded fixture, knowledge, list and view. */
internal fun sitePackJson(fixtureId: String = "LAB-RACK-01", assetId: String = "LAB-SW-01"): String {
    val fixture = Recordings.array("fixtures").first { it.jsonObject["fixtureId"]!!.jsonPrimitive.content == fixtureId }.jsonObject
    val knowledge = JsonObject(Recordings.obj("knowledge").filterKeys { it != "inspectionId" } + ("excluded" to buildJsonArray {
        add(buildJsonObject { put("factId", "LAB-FW-NOTE-01"); put("verdict", "Unverified"); put("reason", "firmware_unknown") })
    }))
    return buildJsonObject {
        put("fixtureId", fixtureId)
        put("fixtureRevision", fixture["fixtureRevision"]!!)
        put("sha256", fixture["sha256"]!!)
        put("generatedAt", "2026-11-03T09:00:00+00:00")
        put("fixture", fixture)
        put("assets", buildJsonArray { add(buildJsonObject { put("assetId", assetId); put("knowledge", knowledge) }) })
        put("inspections", Recordings.array("inspections"))
        put("open", buildJsonArray { add(Recordings.obj("inspection-created")) })
    }.toString()
}

/** An empty answer is an answer: it is saved, so a later failure cannot bring back assets the server removed. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelEmptyFixturesTest {
    private val models = mutableListOf<HomeViewModel>()
    private lateinit var server: MockWebServer
    private val mode = java.util.concurrent.atomic.AtomicReference("full")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/health" -> MockResponse().setBody(Recordings.text("health"))
                    "/v1/fixtures" -> when (mode.get()) {
                        "full" -> MockResponse().setBody(Recordings.text("fixtures"))
                        "empty" -> MockResponse().setBody("[]")
                        else -> MockResponse().setResponseCode(500).setBody("""{"code":"internal","message":"down"}""")
                    }
                    "/v1/inspections" -> MockResponse().setBody("[]")
                    else -> MockResponse().setResponseCode(404).setBody("""{"code":"not_found","message":"no"}""")
                }
            }
            start()
        }
    }

    @AfterEach
    fun tearDown() = runBlocking {
        models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        server.shutdown()
        scope.coroutineContext[Job]?.cancelAndJoin()
        Dispatchers.resetMain()
    }

    @Test fun `an empty fixtures answer replaces the saved copy`(@TempDir dir: File) {
        val json = NetworkModule.json()
        val api = NetworkModule.retrofit(OkHttpClient.Builder().addInterceptor(NetworkModule.baseUrlInterceptor { server.url("/") }).build(), json).create(FieldOpsApi::class.java)
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") })
        fun vm(): HomeViewModel { val snapshots = SnapshotStore(File(dir, "snapshots"), json); val cache = InspectionCache(); return HomeViewModel(api, settings, cache, snapshots, SitePackStore(snapshots, cache)).also(models::add) }
        fun await(vm: HomeViewModel, what: (HomeState) -> Boolean) = runBlocking { withTimeout(5000) { vm.state.first(what) } }
        await(vm()) { it.fixtures.isNotEmpty() && !it.loading }
        mode.set("empty")
        assertTrue(await(vm()) { !it.loading && it.reachable == true }.fixtures.isEmpty(), "the server now lists no assets")
        mode.set("down")
        val later = await(vm()) { !it.loading && it.reachable == true }
        assertTrue(later.fixtures.isEmpty(), "the failed call falls back to the empty answer, not the older full list")
    }
}
