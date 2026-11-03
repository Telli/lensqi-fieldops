package com.lensqi.fieldops.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.lensqi.fieldops.data.InspectionCache
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
                    else -> MockResponse().setResponseCode(404).setBody("""{"code":"not_found","message":"no"}""")
                }
            }
            start()
        }
    }

    @AfterEach
    fun tearDown() { server.shutdown(); scope.cancel(); Dispatchers.resetMain() }

    /** One preferences file per test (DataStore allows one open store per file); each view model is a fresh "app start". */
    private fun settings(dir: File) = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") })

    private fun viewModel(dir: File, settings: SettingsStore): HomeViewModel {
        val json = NetworkModule.json()
        val api = NetworkModule.retrofit(OkHttpClient.Builder().addInterceptor(NetworkModule.baseUrlInterceptor { server.url("/") }).build(), json).create(FieldOpsApi::class.java)
        return HomeViewModel(api, settings, InspectionCache(), SnapshotStore(File(dir, "snapshots"), json))
    }

    private fun await(vm: HomeViewModel, what: (HomeState) -> Boolean): HomeState = runBlocking { withTimeout(5000) { vm.state.first(what) } }

    @Test fun `a failed fixtures call keeps the assets already shown`(@TempDir dir: File) {
        val vm = viewModel(dir, settings(dir))
        val loaded = await(vm) { it.reachable == true && it.fixtures.isNotEmpty() && !it.loading }
        val assets = loaded.fixtures.flatMap { it.assets }.map { it.assetId }
        assertTrue(assets.isNotEmpty())

        fixturesDown.set(true)
        vm.refresh()
        val after = await(vm) { !it.loading }
        assertEquals(true, after.reachable)
        assertEquals(assets, after.fixtures.flatMap { it.assets }.map { it.assetId }, "the server answered health but not fixtures: the list stays")
    }

    @Test fun `a cold start whose fixtures call fails shows the device copy`(@TempDir dir: File) {
        val settings = settings(dir)
        await(viewModel(dir, settings)) { it.fixtures.isNotEmpty() && !it.loading } // saves the snapshot
        fixturesDown.set(true)
        val restarted = await(viewModel(dir, settings)) { !it.loading && it.reachable == true }
        assertTrue(restarted.fixtures.isNotEmpty(), "the snapshot saved by the earlier run fills the list")
    }
}
