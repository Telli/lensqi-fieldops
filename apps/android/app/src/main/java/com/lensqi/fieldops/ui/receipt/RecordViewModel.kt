package com.lensqi.fieldops.ui.receipt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lensqi.fieldops.data.api.*
import com.lensqi.fieldops.ui.common.friendly
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecordUi(val summary: RecordSummary? = null, val error: String? = null, val loading: Boolean = false)

@HiltViewModel
class RecordViewModel @Inject constructor(private val api: FieldOpsApi) : ViewModel() {
    private val mutable = MutableStateFlow(RecordUi())
    val state = mutable.asStateFlow()
    fun load(id: String?) = viewModelScope.launch {
        if (id == null) { mutable.value = RecordUi(error = "This record link couldn’t be read. Return to the inspection and open its record again."); return@launch }
        mutable.value = RecordUi(loading = true)
        mutable.value = when (val result = apiCall { api.receipt(id) }) {
            is ApiOutcome.Ok -> RecordUi(summary = recordSummary(result.value))
            is ApiOutcome.Rejected -> RecordUi(error = friendly(result.code, result.message))
            is ApiOutcome.Unreachable -> RecordUi(error = friendly("offline", result.message))
        }
    }
}
