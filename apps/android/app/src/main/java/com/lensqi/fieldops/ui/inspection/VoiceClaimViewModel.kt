package com.lensqi.fieldops.ui.inspection

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lensqi.fieldops.data.api.ApiOutcome
import com.lensqi.fieldops.data.api.FieldOpsApi
import com.lensqi.fieldops.data.api.apiCall
import com.lensqi.fieldops.voice.ClaimAudioRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

enum class VoiceClaimPhase { Idle, Recording, Transcribing, Review }
data class VoiceClaimUi(
    val phase: VoiceClaimPhase = VoiceClaimPhase.Idle,
    val transcript: String = "",
    val error: String? = null,
)

/**
 * Records a repair claim and turns it into a draft for review. It cannot record a claim: [confirm] only hands the reviewed
 * text back, and the caller sends it through the inspection's one claim path, with that path's idempotency and errors.
 */
@HiltViewModel
class VoiceClaimViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val api: FieldOpsApi,
    private val recorder: ClaimAudioRecorder,
) : ViewModel() {
    private val inspectionId: String = checkNotNull(savedState["inspectionId"])
    private val restored = savedState.get<String>(DRAFT).orEmpty()
    private val local = MutableStateFlow(VoiceClaimUi(if (savedState.contains(DRAFT)) VoiceClaimPhase.Review else VoiceClaimPhase.Idle, restored))
    val state = local.asStateFlow()
    private var operation: Job? = null
    private var stop = AtomicBoolean(false)

    fun startRecording() {
        if (local.value.phase != VoiceClaimPhase.Idle) return
        val stopThisRecording = AtomicBoolean(false)
        stop = stopThisRecording
        local.value = VoiceClaimUi(VoiceClaimPhase.Recording)
        operation = viewModelScope.launch {
            try {
                val audio = recorder.record(stopThisRecording)
                try {
                    coroutineContext.ensureActive()
                    local.update { it.copy(phase = VoiceClaimPhase.Transcribing) }
                    val result = apiCall { api.transcribeClaim(inspectionId, UUID.randomUUID().toString(),
                        MultipartBody.Part.createFormData("audio", "claim.wav", audio.asRequestBody("audio/wav".toMediaType()))) }
                    coroutineContext.ensureActive()
                    when (result) {
                        is ApiOutcome.Ok -> {
                            val text = result.value.transcript.trim()
                            check(text.isNotEmpty()) { "No speech was recognized. Record again or type the claim." }
                            check(text.length <= MAX_CHARS) { "Transcript is too long. Record a shorter claim." }
                            savedState[DRAFT] = text
                            local.value = VoiceClaimUi(VoiceClaimPhase.Review, text)
                        }
                        else -> local.value = VoiceClaimUi(error = message(result))
                    }
                } finally { audio.delete() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { local.value = VoiceClaimUi(error = e.message ?: "Recording failed. Type the claim or try again.") }
        }
    }

    fun stopAndTranscribe() { if (local.value.phase == VoiceClaimPhase.Recording) stop.set(true) }

    fun editTranscript(text: String) {
        if (local.value.phase != VoiceClaimPhase.Review) return
        savedState[DRAFT] = text
        local.update { it.copy(transcript = text, error = null) }
    }

    fun reject() {
        operation?.cancel()
        stop.set(true)
        savedState.remove<String>(DRAFT)
        local.value = VoiceClaimUi()
    }

    /** Leaving the foreground must never leave the microphone open or deliver a late transcript. */
    fun leaveScreen() {
        if (local.value.phase == VoiceClaimPhase.Recording || local.value.phase == VoiceClaimPhase.Transcribing) reject()
    }

    fun permissionDenied() { local.update { it.copy(error = "Microphone permission was denied. Allow it in Settings or type the claim.") } }

    /** The reviewed text, once, for the caller to record; null when there is nothing valid to confirm. */
    fun confirm(): String? {
        val current = local.value
        if (current.phase != VoiceClaimPhase.Review) return null
        val text = current.transcript.trim()
        if (text.isEmpty() || text.length > MAX_CHARS) return null
        savedState.remove<String>(DRAFT)
        local.value = VoiceClaimUi()
        return text
    }

    private fun message(result: ApiOutcome<*>) = when (result) {
        is ApiOutcome.Rejected -> "${result.code}: ${result.message}"
        is ApiOutcome.Unreachable -> "API unreachable. Type the claim or record again when connected."
        is ApiOutcome.Ok -> null
    }

    companion object {
        const val MAX_CHARS = 4000
        private const val DRAFT = "voiceClaimDraft"
    }
}
