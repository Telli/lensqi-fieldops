package com.lensqi.fieldops.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Hands-free commands. Anything else said to LensQi is a question. Same grammar as the web app. */
enum class VoiceCommand { Next, Back, Repeat, Check, Skip, Mute, Unmute, Pause, Resume }

private val phrases = mapOf(
    VoiceCommand.Next to listOf("next", "next step", "done", "im done", "i am done", "finished", "step done", "continue", "go on"),
    VoiceCommand.Back to listOf("back", "go back", "previous", "previous step", "last step"),
    VoiceCommand.Repeat to listOf("repeat", "repeat that", "say that again", "again", "what was that", "read the step"),
    VoiceCommand.Check to listOf("check", "check this", "check step", "check the step", "check my work", "take photo", "take a photo", "take picture", "take a picture"),
    VoiceCommand.Skip to listOf("skip", "skip step", "skip this", "skip this step"),
    VoiceCommand.Mute to listOf("mute", "quiet", "stop talking", "be quiet"),
    VoiceCommand.Unmute to listOf("unmute", "speak", "talk to me", "voice on"),
    // Pause holds everything (speech and live coaching) until "resume"; mute only silences the voice.
    VoiceCommand.Pause to listOf("pause", "hold on", "hang on", "wait", "pause coaching", "stop coaching"),
    VoiceCommand.Resume to listOf("resume", "unpause", "resume coaching", "im back", "i am back"),
)
private val lookup = phrases.flatMap { (c, ps) -> ps.map { it to c } }.toMap()

/** The whole utterance must be a command ("next step", "hey LensQi, check this"); a sentence with more in it is a question. */
fun parseCommand(text: String): VoiceCommand? {
    val t = text.lowercase().replace(Regex("[’']"), "").replace(Regex("[^a-z ]"), " ")
        .replace(Regex("\\b(hey|ok|okay)?\\s*lens\\s?qi\\b"), " ").replace(Regex("\\bplease\\b"), " ").replace(Regex("\\s+"), " ").trim()
    return lookup[t]
}

/** Text-to-speech for guidance, shared by every screen. Speaking a new line interrupts the previous one. */
@Singleton
class Speaker @Inject constructor(@ApplicationContext context: Context) {
    @Volatile private var ready = false
    private val tts = TextToSpeech(context) { status -> ready = status == TextToSpeech.SUCCESS }
    @Volatile var enabled = true
    /** Held while the person said "pause": nothing is spoken, without turning the voice off. */
    @Volatile var held = false
        set(value) { field = value; if (value) stop() }
    private var last = ""

    fun speak(text: String, force: Boolean = false) {
        if (!enabled || held || !ready || text.isBlank() || (!force && text == last)) return
        last = text
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lensqi-${text.hashCode()}")
    }

    fun stop() = tts.stop()
}

class VoiceInputState(val listening: Boolean, val heard: String, val error: String?, val supported: Boolean, val start: () -> Unit, val stop: () -> Unit)

/**
 * Push-to-talk speech recognition. The on-device recognizer is used where Android offers one (Android 12+); otherwise the
 * system recognizer, which may send audio to its provider. Only the final text reaches LensQi.
 */
@Composable
fun rememberVoiceInput(onFinal: (String) -> Unit): VoiceInputState {
    val context = LocalContext.current
    val deliver by rememberUpdatedState(onFinal)
    var listening by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val supported = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    val canOnDevice = remember { supported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
    var onDevice by remember { mutableStateOf(canOnDevice) }
    // The on-device recognizer is preferred; without its language pack the phone's standard recognizer takes over.
    val recognizer = remember(onDevice) {
        if (!supported) null
        else if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
    }
    DisposableEffect(recognizer) { onDispose { recognizer?.destroy() } }
    val begin = {
        heard = ""; error = null; listening = true
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partial: Bundle?) { partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { heard = it } }
            override fun onResults(results: Bundle?) {
                listening = false
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { heard = it; deliver(it) }
            }
            override fun onError(code: Int) {
                listening = false
                // 12/13: ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE (Android 12+), the on-device pack is missing.
                if (onDevice && (code == 12 || code == 13)) { onDevice = false; error = "Switched to the phone's standard speech service. Tap the mic again."; return }
                error = when (code) { SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that."; SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."; else -> "Voice input stopped." }
            }
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
        Unit
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) begin() else error = "Microphone permission is needed for voice." }
    return VoiceInputState(listening, heard, error, supported && recognizer != null,
        start = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        },
        stop = { recognizer?.stopListening(); Unit },
    )
}
