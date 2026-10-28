package com.lensqi.fieldops.ui.home

import com.lensqi.fieldops.ui.common.needsYou
import com.lensqi.fieldops.ui.theme.Appearance
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lensqi.fieldops.data.InspectionCache
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
    val loading: Boolean = false,
    val creating: String? = null,
    val error: String? = null,
    /** The most urgent inspection that has a photo, and that photo's artifact URL, for the Today hero. */
    val focusId: String? = null,
    val focusPhotoUrl: String? = null,
)

/** Shared by the home, inspections, assets, start and profile tabs; everything shown comes from the server or its cached copy. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val api: FieldOpsApi,
    private val settings: SettingsStore,
    private val cache: InspectionCache,
    private val snapshots: SnapshotStore,
) : ViewModel() {
    val appearance = settings.appearance
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
            val fixtures = (apiCall { api.fixtures() } as? ApiOutcome.Ok)?.value.orEmpty()
            val list = (apiCall { api.inspections() } as? ApiOutcome.Ok)?.value
            list?.let { snapshots.save(LIST_KEY, ListSerializer(InspectionSummary.serializer()), it) }
            fixtures.takeIf { it.isNotEmpty() }?.let { snapshots.save(FIXTURES_KEY, ListSerializer(Fixture.serializer()), it) }
            _state.update { it.copy(health = health.value, reachable = true, fixtures = fixtures, inspections = list ?: it.inspections, cachedAt = null, loading = false) }
            loadFocus()
        } else {
            val list = snapshots.load(LIST_KEY, ListSerializer(InspectionSummary.serializer()))
            val fixtures = snapshots.load(FIXTURES_KEY, ListSerializer(Fixture.serializer()))
            _state.update { it.copy(reachable = false, health = null, inspections = list?.value ?: it.inspections, fixtures = fixtures?.value ?: it.fixtures, cachedAt = list?.fetchedAt, loading = false) }
        }
    }

    private suspend fun loadFocus() {
        val focus = needsYou(_state.value.inspections).firstOrNull { it.photos > 0 } ?: return _state.update { it.copy(focusId = null, focusPhotoUrl = null) }
        val names = (apiCall { api.artifacts(focus.inspectionId) } as? ApiOutcome.Ok)?.value.orEmpty()
        val photo = names.filter { it.startsWith("photo-") && !it.contains("-crop-") }
        val view = (apiCall { api.inspection(focus.inspectionId) } as? ApiOutcome.Ok)?.value
        val latest = view?.photos?.lastOrNull()?.evidenceId
        val name = photo.firstOrNull { latest != null && it.startsWith("$latest-") } ?: photo.lastOrNull()
        _state.update { it.copy(focusId = focus.inspectionId, focusPhotoUrl = name?.let { n -> settings.currentBase().resolve("v1/inspections/${focus.inspectionId}/artifacts/$n").toString() }) }
    }

    fun onBaseChanged(value: String) = _state.update { it.copy(apiBase = value) }

    fun saveBase() = viewModelScope.launch {
        if (!settings.setApiBase(_state.value.apiBase)) _state.update { it.copy(error = "Not an http(s) address") } else refresh()
    }

    /** Creates the inspection on the server; the id always comes from the API. */
    fun create(fixtureId: String, assetId: String, onCreated: (String) -> Unit) = viewModelScope.launch {
        _state.update { it.copy(creating = assetId, error = null) }
        when (val r = apiCall { api.createInspection(UUID.randomUUID().toString(), CreateInspectionRequest(fixtureId, assetId)) }) {
            is ApiOutcome.Ok -> { cache.put(r.value); onCreated(r.value.inspectionId) }
            is ApiOutcome.Rejected -> _state.update { it.copy(error = friendly(r.code, r.message)) }
            is ApiOutcome.Unreachable -> _state.update { it.copy(error = friendly("offline", r.message)) }
        }
        _state.update { it.copy(creating = null) }
    }

    fun assetName(assetId: String) = _state.value.fixtures.flatMap { it.assets }.firstOrNull { it.assetId == assetId }?.displayName

    private companion object {
        const val LIST_KEY = "inspections-list"
        const val FIXTURES_KEY = "fixtures"
    }
}
