package com.lensqi.fieldops.data

import com.lensqi.fieldops.data.api.InspectionView
import com.lensqi.fieldops.data.api.closed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Last server view per inspection. Only API responses write here; the app never edits a stage locally. */
@Singleton
class InspectionCache @Inject constructor() {
    private val views = MutableStateFlow<Map<String, InspectionView?>>(emptyMap())

    fun observe(id: String, server: String = ""): Flow<InspectionView?> = views.map { it["$server|$id"] }

    fun hasTerminalSummary(id: String, server: String): Boolean {
        val key = "$server|$id"
        val current = views.value
        return key in current && current[key] == null
    }

    /** Keeps the newest view: responses can arrive out of order when the queue and the screen both call the API. */
    fun put(view: InspectionView, server: String = "") = views.update { current ->
        val key = "$server|${view.inspectionId}"
        val old = current[key]
        if ((key in current && old == null && !view.stage.closed) || (old != null && old.lastSequence > view.lastSequence)) current else current + (key to view)
    }

    /** A terminal summary prevents a late response from reviving an obsolete open view. */
    fun markClosed(id: String, server: String) = views.update { current ->
        val key = "$server|$id"
        if (current[key]?.stage?.closed == true) current else current + (key to null)
    }
}
