package com.lensqi.fieldops.wearables

import com.lensqi.fieldops.data.api.FieldOpsApi
import com.lensqi.fieldops.domain.live.LiveResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

enum class LiveSessionKind { Inspection, Task }

/**
 * What live glasses assistance serves (GT-R12.2): an inspection, or a guided task whose server coaches its current step.
 * [targetConcept] is the part distance coaching is about (a task step's main `lookFor`).
 */
data class LiveSession(val id: String, val kind: LiveSessionKind, val targetConcept: String? = null,
    /** What the technician calls it ("Switch LAB-SW-01", "Step 2 · Loosen the drain plug"), for the screen and the notification. */
    val label: String? = null) {
    /** The same work: id, kind and target; a different label alone is not another session. */
    fun sameAs(other: LiveSession?): Boolean = other != null && id == other.id && kind == other.kind && targetConcept == other.targetConcept
    /** Sends one sampled frame to the live endpoint of this session's kind; the server decides what the guidance is about. */
    suspend fun analyze(api: FieldOpsApi, kind: String, jpeg: ByteArray, frameId: String): LiveResponse {
        val image = MultipartBody.Part.createFormData("image", "glasses-preview.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
        val frame = frameId.toRequestBody("text/plain".toMediaType())
        return when (this.kind) {
            LiveSessionKind.Inspection -> api.liveFrame(id, kind, image, frame)
            LiveSessionKind.Task -> api.taskLiveFrame(id, kind, image, frame)
        }
    }
}
