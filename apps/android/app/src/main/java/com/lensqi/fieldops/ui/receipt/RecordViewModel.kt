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

data class RecordUi(val summary: RecordSummary? = null, val error: String? = null, val loading: Boolean = false, val exporting: Boolean = false)

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

    suspend fun export(id: String, directory: java.io.File): java.io.File? {
        mutable.value = mutable.value.copy(exporting = true, error = null)
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                directory.mkdirs()
                directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000 }?.forEach { it.delete() }
                val file = java.io.File(directory, "fieldproof-${java.util.UUID.randomUUID()}.zip")
                try {
                    api.receiptBundle(id).use { body -> body.byteStream().use { input -> file.outputStream().use(input::copyTo) } }
                    file
                } catch (e: Exception) { file.delete(); throw e }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            mutable.value = mutable.value.copy(error = "The record could not be exported. Check the connection and the record’s integrity, then try again.")
            null
        } finally { mutable.value = mutable.value.copy(exporting = false) }
    }
}
