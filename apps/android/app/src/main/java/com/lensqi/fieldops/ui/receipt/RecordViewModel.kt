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

/** [error] is a load failure (Try again reloads); [exportError] is an export failure and leaves the loaded summary in place. */
data class RecordUi(val summary: RecordSummary? = null, val error: String? = null, val loading: Boolean = false, val exporting: Boolean = false, val exportError: String? = null)

/**
 * The server answers the bundle request with 409 receipt_invalid when the record fails its own integrity check; that is a
 * fact about the record, not the connection, and no retry changes it. Anything else is a transfer failure.
 */
fun exportFailure(e: Exception): String =
    if (e is retrofit2.HttpException && e.code() == 409) EXPORT_INTEGRITY_FAILED else EXPORT_FAILED

const val EXPORT_INTEGRITY_FAILED = "The record failed its integrity check and cannot be exported."
const val EXPORT_FAILED = "The record could not be exported. Check the connection, then try again."

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
        mutable.value = mutable.value.copy(exporting = true, exportError = null)
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
            mutable.value = mutable.value.copy(exportError = exportFailure(e))
            null
        } finally { mutable.value = mutable.value.copy(exporting = false) }
    }
}
