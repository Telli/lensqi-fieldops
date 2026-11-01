package com.lensqi.fieldops.data.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow


/** Bounded, on-device operational history. No payloads, credentials, image/audio or automatic uploads. */
object DiagnosticHistory {
    private val mutable = MutableStateFlow<List<String>>(emptyList())
    val entries = mutable.asStateFlow()
    @Synchronized fun record(area: String, code: String, status: Int? = null) {
        val safeArea = area.takeIf { it.matches(Regex("[a-z_]{1,40}")) } ?: "operation"
        val safeCode = code.takeIf { it.matches(Regex("[a-z_0-9]{1,64}")) } ?: "unrecognized_error"
        val entry = "area=$safeArea code=$safeCode" + (status?.let { " status=$it" } ?: "")
        runCatching { android.util.Log.i("FieldOpsDiagnostics", entry) }
        mutable.value = (listOf(entry) + mutable.value).take(40)
    }
    fun clear() { mutable.value = emptyList() }
}
