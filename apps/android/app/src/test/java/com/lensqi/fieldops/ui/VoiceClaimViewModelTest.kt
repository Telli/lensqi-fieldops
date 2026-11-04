package com.lensqi.fieldops.ui

import androidx.lifecycle.SavedStateHandle
import com.lensqi.fieldops.data.api.*
import com.lensqi.fieldops.ui.inspection.VoiceClaimPhase
import com.lensqi.fieldops.ui.inspection.VoiceClaimViewModel
import com.lensqi.fieldops.voice.ClaimAudioRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import okhttp3.MultipartBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceClaimViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val files = mutableListOf<File>()
    private val api = FakeApi()
    private var recording: suspend (AtomicBoolean) -> File = { audio() }
    private val saved = SavedStateHandle(mapOf("inspectionId" to "inspection-test"))
    private fun vm() = VoiceClaimViewModel(saved, api, object : ClaimAudioRecorder {
        override suspend fun record(stop: AtomicBoolean) = recording(stop)
    })
    private fun audio() = File.createTempFile("voice-test", ".wav").also { it.writeBytes(byteArrayOf(1, 2)); files += it }

    @BeforeEach fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterEach fun cleanup() { Dispatchers.resetMain(); files.forEach { it.delete() } }

    @Test fun `transcription is only a draft - editing and explicit confirmation hand the text back exactly once`() = runTest(dispatcher) {
        val vm = vm()
        assertNull(vm.confirm())
        vm.startRecording()
        runCurrent()
        assertEquals(VoiceClaimPhase.Review, vm.state.value.phase)
        assertEquals("Reseated cable", vm.state.value.transcript)
        assertTrue(files.none { it.exists() }, "Uploaded audio must be deleted")
        vm.editTranscript("  Reseated the yellow cable at port 2  ")
        assertEquals("Reseated the yellow cable at port 2", vm.confirm())
        assertNull(vm.confirm())
        assertEquals(VoiceClaimPhase.Idle, vm.state.value.phase)
        assertNull(saved.get<String>("voiceClaimDraft"))
    }

    @Test fun `reject and blank or overlong review confirm nothing and restored drafts still require confirmation`() = runTest(dispatcher) {
        val vm = vm()
        vm.startRecording(); runCurrent()
        vm.editTranscript("   ")
        assertNull(vm.confirm())
        vm.editTranscript("x".repeat(VoiceClaimViewModel.MAX_CHARS + 1))
        assertNull(vm.confirm())
        assertEquals(VoiceClaimPhase.Review, vm.state.value.phase)
        vm.reject()
        assertNull(vm.confirm())
        assertEquals(VoiceClaimPhase.Idle, vm.state.value.phase)
        saved["voiceClaimDraft"] = "Edited draft"
        val restored = vm()
        assertEquals(VoiceClaimPhase.Review, restored.state.value.phase)
        assertEquals("Edited draft", restored.state.value.transcript)
        restored.reject()
        assertNull(saved.get<String>("voiceClaimDraft"))
    }

    @Test fun `recording error permission denial and empty transcription preserve typed fallback`() = runTest(dispatcher) {
        val vm = vm()
        vm.permissionDenied()
        assertTrue(vm.state.value.error!!.contains("permission"))
        recording = { throw IOException("Microphone unavailable") }
        vm.startRecording(); runCurrent()
        assertEquals(VoiceClaimPhase.Idle, vm.state.value.phase)
        assertEquals(0, api.transcriptions)
        recording = { audio() }; api.text = "   "
        vm.startRecording(); runCurrent()
        assertEquals(VoiceClaimPhase.Idle, vm.state.value.phase)
        assertNotNull(vm.state.value.error)
    }

    @Test fun `the server's no speech answer is shown with its code and leaves no draft`() = runTest(dispatcher) {
        val vm = vm()
        api.transcriptionError = HttpException(Response.error<Any>(409, """{"code":"no_speech","message":"No speech was recognized."}""".toResponseBody()))
        vm.startRecording(); runCurrent()
        assertEquals(VoiceClaimPhase.Idle, vm.state.value.phase)
        assertEquals("no_speech: No speech was recognized.", vm.state.value.error)
        assertNull(saved.get<String>("voiceClaimDraft"))
        assertTrue(files.none { it.exists() })
    }

    @Test fun `transcription failure deletes audio and leaving screen cancels pending transcription`() = runTest(dispatcher) {
        val vm = vm()
        api.transcriptionError = IOException("offline")
        vm.startRecording(); runCurrent()
        assertEquals(VoiceClaimPhase.Idle, vm.state.value.phase)
        assertTrue(files.none { it.exists() })
        api.transcriptionError = null
        api.pendingTranscript = CompletableDeferred()
        vm.startRecording(); runCurrent()
        assertEquals(VoiceClaimPhase.Transcribing, vm.state.value.phase)
        vm.leaveScreen(); runCurrent()
        api.pendingTranscript!!.complete(VoiceTranscript("late words", "test")); runCurrent()
        assertEquals(VoiceClaimPhase.Idle, vm.state.value.phase)
        assertEquals("", vm.state.value.transcript)
        assertTrue(files.none { it.exists() })
    }

    @Test fun `stop signals recorder and leaving screen cancels microphone ownership`() = runTest(dispatcher) {
        var stop: AtomicBoolean? = null
        var released = false
        recording = { signal -> stop = signal; try { awaitCancellation() } finally { released = true } }
        val vm = vm()
        vm.startRecording(); runCurrent()
        assertEquals(VoiceClaimPhase.Recording, vm.state.value.phase)
        assertNull(vm.confirm())
        vm.stopAndTranscribe()
        assertTrue(stop!!.get())
        vm.leaveScreen(); runCurrent()
        assertTrue(released)
        assertEquals(0, api.transcriptions)
    }

    @Test fun `leaving during review keeps the draft`() = runTest(dispatcher) {
        val vm = vm()
        vm.startRecording(); runCurrent()
        vm.editTranscript("Reseated at port 2")
        vm.leaveScreen(); runCurrent()
        assertEquals(VoiceClaimPhase.Review, vm.state.value.phase)
        assertEquals("Reseated at port 2", saved.get<String>("voiceClaimDraft"))
    }

    private class FakeApi : FieldOpsApi by unusedApi() {
        var text = "Reseated cable"
        var transcriptions = 0
        var transcriptionError: Exception? = null
        var pendingTranscript: CompletableDeferred<VoiceTranscript>? = null
        override suspend fun transcribeClaim(id: String, key: String, audio: MultipartBody.Part): VoiceTranscript {
            transcriptions++
            transcriptionError?.let { throw it }
            return pendingTranscript?.await() ?: VoiceTranscript(text, "test-voice")
        }
    }
    companion object {
        private fun unusedApi(): FieldOpsApi = Proxy.newProxyInstance(FieldOpsApi::class.java.classLoader, arrayOf(FieldOpsApi::class.java)) {
            _, method, _ -> error("Unexpected API call: ${method.name}")
        } as FieldOpsApi
    }
}
