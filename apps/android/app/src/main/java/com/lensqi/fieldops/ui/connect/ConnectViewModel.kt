package com.lensqi.fieldops.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lensqi.fieldops.data.InspectionCache
import com.lensqi.fieldops.data.api.ApiOutcome
import com.lensqi.fieldops.data.api.CreateInspectionRequest
import com.lensqi.fieldops.data.api.FieldOpsApi
import com.lensqi.fieldops.data.api.Fixture
import com.lensqi.fieldops.data.api.Health
import com.lensqi.fieldops.data.api.apiCall
import com.lensqi.fieldops.data.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ConnectState(
    val apiBase: String = "",
    val checking: Boolean = false,
    val health: Health? = null,
    val fixtures: List<Fixture> = emptyList(),
    val error: String? = null,
    val creating: Boolean = false,
)

@HiltViewModel
class ConnectViewModel @Inject constructor(private val api: FieldOpsApi, private val settings: SettingsStore, private val cache: InspectionCache) : ViewModel() {
    private val _state = MutableStateFlow(ConnectState())
    val state: StateFlow<ConnectState> = _state

    init {
        viewModelScope.launch {
            _state.update { it.copy(apiBase = settings.apiBase.first()) }
            check()
        }
    }

    /** Creates the inspection on the server; the id comes back from the API, never from the device. */
    fun createInspection(fixtureId: String, assetId: String, onCreated: (String) -> Unit) = viewModelScope.launch {
        _state.update { it.copy(creating = true, error = null) }
        when (val r = apiCall { api.createInspection(UUID.randomUUID().toString(), CreateInspectionRequest(fixtureId, assetId)) }) {
            is ApiOutcome.Ok -> { cache.put(r.value); onCreated(r.value.inspectionId) }
            is ApiOutcome.Rejected -> _state.update { it.copy(error = "${r.code}: ${r.message}") }
            is ApiOutcome.Unreachable -> _state.update { it.copy(error = "Unreachable: ${r.message}") }
        }
        _state.update { it.copy(creating = false) }
    }

    fun onBaseChanged(value: String) = _state.update { it.copy(apiBase = value) }

    fun check() = viewModelScope.launch {
        if (!settings.setApiBase(_state.value.apiBase)) {
            _state.update { it.copy(error = "Not an http(s) URL", health = null) }
            return@launch
        }
        _state.update { it.copy(checking = true, error = null) }
        val health = apiCall { api.health() }
        val fixtures = if (health is ApiOutcome.Ok) apiCall { api.fixtures() } else null
        _state.update {
            it.copy(
                checking = false,
                health = (health as? ApiOutcome.Ok)?.value,
                fixtures = (fixtures as? ApiOutcome.Ok)?.value.orEmpty(),
                error = when (health) {
                    is ApiOutcome.Ok -> null
                    is ApiOutcome.Rejected -> "${health.code}: ${health.message}"
                    is ApiOutcome.Unreachable -> "Unreachable: ${health.message}"
                },
            )
        }
    }
}
