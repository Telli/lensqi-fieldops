package com.lensqi.fieldops.ui.home

import com.lensqi.fieldops.ui.common.needsYou
import com.lensqi.fieldops.ui.theme.Appearance
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lensqi.fieldops.data.InspectionCache
import com.lensqi.fieldops.data.SitePackReceipt
import com.lensqi.fieldops.data.SitePackStore
import com.lensqi.fieldops.data.SnapshotStore
import com.lensqi.fieldops.data.api.ApiOutcome
import com.lensqi.fieldops.data.api.CreateInspectionRequest
import com.lensqi.fieldops.data.api.FieldOpsApi
import com.lensqi.fieldops.data.api.Fixture
import com.lensqi.fieldops.data.api.Health
import com.lensqi.fieldops.data.api.InspectionSummary
import com.lensqi.fieldops.data.api.apiCall
import com.lensqi.fieldops.data.settings.SettingsStore
import com.lensqi.fieldops.ui.common.friendly
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID
import javax.inject.Inject

data class HomeState(
    val apiBase: String = "",
    val health: Health? = null,
    val reachable: Boolean? = null,
    val fixtures: List<Fixture> = emptyList(),
    val inspections: List<InspectionSummary> = emptyList(),
    /** Set when the list shown is the device copy because the server is unreachable. */
    val cachedAt: String? = null,
    /** Set when the assets shown are the device copy: the fixtures call failed (even with the server reachable) or the server is unreachable. */
    val fixturesCachedAt: String? = null,
    /** The last fixtures call did not answer (failed, or the server was unreachable); an empty list then means nothing is known, not that there are no assets. */
    val fixturesFailed: Boolean = false,
    /** When the assets on screen were last fetched from the server, so a list kept in memory after a failed call is dated too. */
    val fixturesFetchedAt: String? = null,
    val loading: Boolean = false,
    val creating: String? = null,
    val error: String? = null,
    /** The most urgent inspection that has a photo, and that photo's artifact URL, for the Today hero. */
    val focusId: String? = null,
    val focusPhotoUrl: String? = null,
    /** Latest photo URL per asset, loaded when the asset's page opens. */
    val assetPhotos: Map<String, String> = emptyMap(),
    /** Site packs saved on this phone, by fixture: when, which revision, how much it covered. */
    val sitePacks: Map<String, SitePackReceipt> = emptyMap(),
    /** The fixture whose site pack is downloading now. */
    val downloadingPack: String? = null,
    /** What the last site-pack download left on the phone; cleared by the next download. Not an error. */
    val packNotice: String? = null,
    /** Why the last site pack download failed; kept apart from [error] so an unrelated stale error never shows under the button. */
    val packError: String? = null,
)

/** Shared by the home, inspections, assets, start and profile tabs; everything shown comes from the server or its cached copy. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val api: FieldOpsApi,
    private val settings: SettingsStore,
    private val cache: InspectionCache,
    private val snapshots: SnapshotStore,
    private val sitePacks: SitePackStore,
) : ViewModel() {
    private val serverBase = settings.currentBase().toString()
    val diagnostics = settings.diagnostics
    /** A failed preference write (DataStore throws IOException) is reported on Settings instead of crashing the app. */
    fun setDiagnostics(enabled: Boolean) = viewModelScope.launch {
        try { settings.setDiagnostics(enabled) } catch (e: java.io.IOException) {
            _state.update { it.copy(error = "The diagnostics setting couldn't be saved. Try again.") }
        }
    }
    val appearance = settings.appearance
    val person = settings.person
    /** Saves height (as typed: 6'2", 188 cm…) and tools; false when the height can't be read. */
    fun savePerson(heightText: String, tools: Set<String>, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val height = if (heightText.isBlank()) null else com.lensqi.fieldops.data.settings.parseHeightCm(heightText)
        if (heightText.isNotBlank() && height == null) { onDone(false); return@launch }
        settings.setPerson(com.lensqi.fieldops.data.settings.PersonProfile(height, tools)); onDone(true)
    }
    fun setAppearance(value: Appearance) = viewModelScope.launch { settings.setAppearance(value) }

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state

    init {
        viewModelScope.launch {
            _state.update { it.copy(apiBase = settings.apiBase.first()) }
            refresh()
        }
    }

    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        val health = apiCall { api.health() }
        if (health is ApiOutcome.Ok) {
            // A failed list call keeps the device copy of the assets, marked with when it was fetched; it never empties them.
            val fixtures = (apiCall { api.fixtures() } as? ApiOutcome.Ok)?.value
            val list = (apiCall { api.inspections() } as? ApiOutcome.Ok)?.value
            list?.let { snapshots.saveOn(LIST_KEY, ListSerializer(InspectionSummary.serializer()), it) }
            // Every answer is saved, an empty list too, so a later failure never resurrects assets the server has removed.
            fixtures?.let { snapshots.saveOn(FIXTURES_KEY, ListSerializer(Fixture.serializer()), it) }
            // After a failed call the list this run already fetched is the newest known (a snapshot save can fail and leave an
            // older copy on disk); the saved copy is read only when nothing was fetched yet, on a cold start.
            val kept = if (fixtures == null && _state.value.fixturesFetchedAt == null) snapshots.loadOn(FIXTURES_KEY, ListSerializer(Fixture.serializer())) else null
            val now = java.time.Instant.now().toString()
            _state.update {
                val fetchedAt = if (fixtures != null) now else it.fixturesFetchedAt ?: kept?.fetchedAt
                it.copy(health = health.value, reachable = true, fixtures = fixtures ?: (if (it.fixturesFetchedAt != null) it.fixtures else kept?.value ?: it.fixtures),
                    // Kept assets are always dated: when this run fetched them, else the saved copy's time.
                    fixturesCachedAt = if (fixtures != null) null else fetchedAt, fixturesFailed = fixtures == null, fixturesFetchedAt = fetchedAt,
                    inspections = list ?: it.inspections, cachedAt = null, loading = false)
            }
            loadFocus()
            loadReceipts()
        } else {
            val list = snapshots.loadOn(LIST_KEY, ListSerializer(InspectionSummary.serializer()))
            // Same rule: the list this run fetched outranks the saved copy.
            val fixtures = if (_state.value.fixturesFetchedAt == null) snapshots.loadOn(FIXTURES_KEY, ListSerializer(Fixture.serializer())) else null
            _state.update {
                val fetchedAt = it.fixturesFetchedAt ?: fixtures?.fetchedAt
                it.copy(reachable = false, health = null, inspections = list?.value ?: it.inspections, fixtures = fixtures?.value ?: it.fixtures, cachedAt = list?.fetchedAt,
                    fixturesCachedAt = fetchedAt, fixturesFetchedAt = fetchedAt, fixturesFailed = true, loading = false)
            }
            loadReceipts()
        }
    }

    /** What each known fixture's site pack left on this phone, so the screens can say "Saved on this phone at …". */
    private suspend fun loadReceipts() {
        val receipts = _state.value.fixtures.mapNotNull { f -> sitePacks.receipt(f.fixtureId, serverBase)?.value?.let { f.fixtureId to it } }.toMap()
        _state.update { it.copy(sitePacks = it.sitePacks + receipts) }
    }

    /**
     * Fetches one fixture's site pack (site record, every asset's documentation, the open inspections; never photos) and
     * leaves it under the snapshot keys the screens read offline. Online only: the pack is a server response, nothing else.
     */
    fun downloadSitePack(fixtureId: String) = viewModelScope.launch {
        if (_state.value.downloadingPack != null) return@launch
        _state.update { it.copy(downloadingPack = fixtureId, packNotice = null, packError = null) }
        when (val r = apiCall { api.sitePack(fixtureId) }) {
            is ApiOutcome.Ok -> {
                sitePacks.store(r.value, serverBase)
                val receipt = sitePacks.receipt(fixtureId, serverBase)?.value
                _state.update {
                    it.copy(sitePacks = if (receipt != null) it.sitePacks + (fixtureId to receipt) else it.sitePacks,
                        packNotice = "Site pack saved on this phone: ${r.value.assets.size} asset${if (r.value.assets.size == 1) "" else "s"}, ${r.value.open.size} open inspection${if (r.value.open.size == 1) "" else "s"}. Photos are not included.")
                }
            }
            is ApiOutcome.Rejected -> _state.update { it.copy(packError = friendly(r.code, r.message)) }
            is ApiOutcome.Unreachable -> _state.update { it.copy(packError = friendly("offline", r.message)) }
        }
        _state.update { it.copy(downloadingPack = null) }
    }

    private suspend fun loadFocus() {
        val focus = needsYou(_state.value.inspections).firstOrNull { it.photos > 0 } ?: return _state.update { it.copy(focusId = null, focusPhotoUrl = null) }
        val url = latestPhotoUrl(focus.inspectionId)
        _state.update { it.copy(focusId = focus.inspectionId, focusPhotoUrl = url) }
    }

    /** The asset's most recent photo, from its newest inspection that has one. */
    fun loadAssetPhoto(assetId: String) = viewModelScope.launch {
        val withPhoto = _state.value.inspections.firstOrNull { it.assetId == assetId && it.photos > 0 } ?: return@launch
        latestPhotoUrl(withPhoto.inspectionId)?.let { url -> _state.update { it.copy(assetPhotos = it.assetPhotos + (assetId to url)) } }
    }

    private suspend fun latestPhotoUrl(inspectionId: String): String? {
        val names = (apiCall { api.artifacts(inspectionId) } as? ApiOutcome.Ok)?.value.orEmpty()
        val photo = names.filter { it.startsWith("photo-") && !it.contains("-crop-") }
        val view = (apiCall { api.inspection(inspectionId) } as? ApiOutcome.Ok)?.value
        val latest = view?.photos?.lastOrNull()?.evidenceId
        val name = photo.firstOrNull { latest != null && it.startsWith("$latest-") } ?: photo.lastOrNull()
        return name?.let { n -> settings.currentBase().resolve("v1/inspections/$inspectionId/artifacts/$n").toString() }
    }

    fun onBaseChanged(value: String) = _state.update { it.copy(apiBase = value) }

    fun saveBase(onSaved: () -> Unit) = viewModelScope.launch {
        if (!settings.setApiBase(_state.value.apiBase)) _state.update { it.copy(error = "Not an http(s) address") } else onSaved()
    }

    /** Creates the inspection on the server; the id always comes from the API. */
    fun create(fixtureId: String, assetId: String, onCreated: (String) -> Unit) = viewModelScope.launch {
        _state.update { it.copy(creating = assetId, error = null) }
        when (val r = apiCall { api.createInspection(UUID.randomUUID().toString(), CreateInspectionRequest(fixtureId, assetId)) }) {
            is ApiOutcome.Ok -> { cache.put(r.value, serverBase); onCreated(r.value.inspectionId) }
            is ApiOutcome.Rejected -> _state.update { it.copy(error = friendly(r.code, r.message)) }
            is ApiOutcome.Unreachable -> _state.update { it.copy(error = friendly("offline", r.message)) }
        }
        _state.update { it.copy(creating = null) }
    }

    fun assetName(assetId: String) = _state.value.fixtures.flatMap { it.assets }.firstOrNull { it.assetId == assetId }?.displayName

    private val LIST_KEY get() = SnapshotStore.key(serverBase, "inspections-list")
    private val FIXTURES_KEY get() = SnapshotStore.key(serverBase, "fixtures")
}
