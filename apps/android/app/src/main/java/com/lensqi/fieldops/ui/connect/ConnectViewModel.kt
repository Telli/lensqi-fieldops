package com.lensqi.fieldops.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lensqi.fieldops.data.api.ApiOutcome
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
import javax.inject.Inject

data class ConnectState(
    val apiBase: String = "",
    val checking: Boolean = false,
    val health: Health? = null,
    val fixtures: List<Fixture> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class ConnectViewModel @Inject constructor(private val api: FieldOpsApi, private val settings: SettingsStore) : ViewModel() {
    private val _state = MutableStateFlow(ConnectState())
    val state: StateFlow<ConnectState> = _state

    init {
        viewModelScope.launch {
            _state.update { it.copy(apiBase = settings.apiBase.first()) }
            check()
        }
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
