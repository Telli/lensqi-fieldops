package com.lensqi.fieldops.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MediaType.Companion.toMediaType

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

/** What screens and view models need from speech; [Speaker] is the device's, tests use their own. */
interface SpeechOutput {
    var enabled: Boolean
    /** Held while the person said "pause": nothing is spoken, without turning the voice off. */
    var held: Boolean
    /**
     * Set while the microphone listens: nothing is spoken, so LensQi never hears itself as the question. Forced lines
     * cut off or arriving meanwhile are read once it is cleared.
     */
    var listening: Boolean
    /** True while a hint would be dropped: held, listening, or a step, answer or check result still being read. */
    val busy: Boolean
    /**
     * True while the engine is reading a line, forced or not. Hands-free waits for it before opening the microphone, so
     * LensQi's own voice is never taken as a question. The device speaker reads it from the engine's progress callbacks,
     * with [SpeechTurns]' estimate where the engine does not report an end.
     */
    val speaking: Boolean
    /**
     * [force] marks a line that must be heard in full (a step, an answer, a check result, an error): a hint never cuts it
     * off, and another forced line plays after it. [replace] (a new step, "repeat", "mute") cuts off the forced lines
     * playing or waiting, which are out of date.
     */
    fun speak(text: String, force: Boolean = false, replace: Boolean = false)
    fun stop()
}

/** "Mute" and "unmute" by voice, said aloud so a hands-free user knows it took. */
fun SpeechOutput.mute() { speak("Voice off. Say unmute to turn it back on.", force = true, replace = true); enabled = false }
fun SpeechOutput.unmute() { enabled = true; speak("Voice on.", force = true) }

enum class Play { Now, After, Drop }

/**
 * Which line plays. A forced line runs to its end: a hint arriving meanwhile is dropped (the screen's gate offers it
 * again once [playing] is false) and another forced line plays after it. The TTS engine reports each end;
 * [maxMsPerChar] caps the wait if it never does. While the microphone listens, forced lines wait in order until [release].
 */
class SpeechTurns(private val clock: () -> Long, private val maxMsPerChar: Long = 90) {
    /** Forced lines playing or queued, by utterance id, in play order. */
    private val forced = LinkedHashMap<String, String>()
    private var until = 0L
    private val waiting = mutableListOf<String>()
    val playing get() = forced.isNotEmpty() && clock() < until

    fun begin(id: String, text: String, force: Boolean, replace: Boolean = false): Play {
        val after = playing && !replace
        if (!force) return if (after) Play.Drop else { forced.clear(); Play.Now }
        if (!after) forced.clear()
        forced[id] = text
        until = (if (after) until else clock()) + 3000 + maxMsPerChar * text.length
        return if (after) Play.After else Play.Now
    }
    fun finished(id: String) { forced.remove(id) }
    /** Speech was stopped: nothing playing, queued or waiting for the microphone is read. */
    fun clear() { forced.clear(); waiting.clear() }

    /** The microphone opens: forced lines still to be heard wait and are read again from their start. */
    fun hold() { if (playing) waiting += forced.values; forced.clear() }
    /** A forced line arriving while the microphone listens; [replace] drops the ones it makes out of date. */
    fun hold(text: String, replace: Boolean) { if (replace) waiting.clear(); waiting += text }
    fun release(): List<String> = waiting.toList().also { waiting.clear() }
}

/**
 * What a speaker does with lines before its engine has started. The first step of a task opened cold arrives while
 * TextToSpeech is still initialising, so forced lines (a step, an answer, a check result) wait in order and are read once
 * the engine is ready. A replacing line (a new step) drops the ones before it, since they are out of date by then; a
 * check result or an error keeps the step it follows. Hints are dropped: the screen offers them again. An engine that
 * fails to start reads nothing, as before.
 */
class StartupBuffer {
    private var started = false
    private var failed = false
    private val waiting = mutableListOf<String>()
    /** True when the line may play now; false when it was kept for [ready] or dropped. */
    @Synchronized fun offer(text: String, force: Boolean, replace: Boolean = false): Boolean {
        if (started) return true
        if (force && !failed) { if (replace) waiting.clear(); waiting += text }
        return false
    }
    /** The engine reported its start: the lines kept for it, in order, to play now. */
    @Synchronized fun ready(success: Boolean): List<String> {
        started = success; failed = !success
        return waiting.toList().also { waiting.clear() }.takeIf { success } ?: emptyList()
    }
    /** Speech was stopped (the screen closed): lines waiting for the engine belong to it and are never read. */
    @Synchronized fun clear() { waiting.clear() }
}

/** Text-to-speech for guidance, shared by every screen. A new line interrupts the previous one, unless that one is forced. */
@Singleton
class Speaker @Inject constructor(@ApplicationContext context: Context) : SpeechOutput {
    private val startup = StartupBuffer()
    private val tts = TextToSpeech(context) { status -> startup.ready(status == TextToSpeech.SUCCESS).forEach { play(it, force = true, replace = false) } }
    private val turns = SpeechTurns(SystemClock::elapsedRealtime)
    @Volatile override var enabled = true
    @Volatile override var held = false
        set(value) { field = value; if (value) stop() }
    @Volatile override var listening = false
        set(value) {
            field = value
            if (value) { synchronized(turns) { turns.hold() }; tts.stop() }
            else synchronized(turns) { turns.release() }.forEach { play(it, force = true, replace = false) }
        }
    override val busy get() = held || listening || synchronized(turns) { turns.playing }
    // The engine's own word on whether it is reading (any line, a hint included); the turn estimate covers an engine that
    // never reports an end, and a queued forced line not yet started.
    @Volatile private var reading = false
    override val speaking get() = reading || synchronized(turns) { turns.playing }
    private var last = ""
    private var count = 0

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { reading = true }
            override fun onDone(id: String?) { reading = false; id?.let { synchronized(turns) { turns.finished(it) } } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { reading = false; id?.let { synchronized(turns) { turns.finished(it) } } }
            override fun onError(id: String?, errorCode: Int) { reading = false; id?.let { synchronized(turns) { turns.finished(it) } } }
            override fun onStop(id: String?, interrupted: Boolean) { reading = false; id?.let { synchronized(turns) { turns.finished(it) } } }
        })
    }

    // What is spoken is what the screen shows: a step, an answer or a check reason names the equipment in its own words
    // ("the Delta cartridge"); internal status strings are made readable by their callers before they reach here.
    override fun speak(text: String, force: Boolean, replace: Boolean) = play(text, force, replace)

    private fun play(spoken: String, force: Boolean, replace: Boolean) {
        if (!enabled || held || spoken.isBlank()) return
        if (!startup.offer(spoken, force, replace)) return
        if (listening) { if (force) synchronized(turns) { turns.hold(spoken, replace) }; return }
        if (!force && spoken == last) return
        val id: String; val play: Play
        synchronized(turns) { id = "lensqi-${++count}"; play = turns.begin(id, spoken, force, replace) }
        if (play == Play.Drop) return
        last = spoken
        tts.speak(spoken, if (play == Play.After) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, id)
    }

    override fun stop() { startup.clear(); synchronized(turns) { turns.clear() }; tts.stop(); reading = false }
}

/**
 * Keeps [speech] quiet exactly while the recognizer listens, so LensQi never hears itself. The hold is set before
 * listening starts (which stops any line playing) and released before a result is delivered, so a command's spoken
 * reply ("Voice on.", a repeated step) is heard.
 */
class ListeningHold(private val speech: SpeechOutput?) {
    fun started() { speech?.listening = true }
    fun ended() { speech?.listening = false }
    /** The screen closed while listening: what waited for the microphone belongs to it and is never read elsewhere. */
    fun cancel() { speech?.stop(); speech?.listening = false }
    fun result(text: String?, deliver: (String) -> Unit) { ended(); text?.takeIf { it.isNotBlank() }?.let(deliver) }
}

class VoiceInputState(val listening: Boolean, val heard: String, val error: String?, val supported: Boolean, val start: () -> Unit, val stop: () -> Unit,
    /** The microphone was refused and Android will not ask again; [openSettings] is the way to allow it. Typing still works. */
    val settingsNeeded: Boolean = false, val openSettings: () -> Unit = {},
    /** The recognizer in use is Android's on-device one: no audio leaves the phone. */
    val onDevice: Boolean = false,
    /**
     * Times the microphone failed to open or stopped for a reason other than silence: a start that threw, a refused
     * permission, or an error other than "nothing heard". Hands-free watches it and turns itself off when it moves.
     */
    val startFailures: Int = 0,
    /** Ends an open listen and delivers nothing from it, unlike [stop], which still hands over the final text. */
    val abort: () -> Unit = stop)

/** Discloses the current server-mediated Meta transcription path. */
fun speechNotice(@Suppress("UNUSED_PARAMETER") onDevice: Boolean): String =
    "Audio is sent through LensQi to Meta Voice Transcribe. Internet required; you can type instead."

/** On the camera, a question also carries what the camera sees now, whether it was spoken or typed. */
const val ASK_NOTICE = "Asking sends your voice or text and camera view to Meta via LensQi."

/** The recognizer heard nothing: an ordinary outcome, not a failure; said once by push-to-talk and never in hands-free. */
const val NOTHING_HEARD = "I didn't catch that."

/**
 * The decisions behind hands-free, kept pure so they are tested: when the microphone opens again, what ends hands-free,
 * which recognizer errors are failures, and what may be said while coaching is paused.
 */
object HandsFreePolicy {
    /** The microphone opens only when nothing else holds the floor: not listening already, LensQi not talking, no request pending. */
    fun shouldListen(on: Boolean, supported: Boolean, listening: Boolean, speaking: Boolean, busy: Boolean, started: Boolean = true): Boolean =
        started && on && supported && !listening && !speaking && !busy
    /** Hands-free ends when the microphone is blocked for good, or failed to start since it was switched on. */
    fun shouldStop(settingsNeeded: Boolean, startFailuresNow: Int, startFailuresAtOn: Int): Boolean =
        settingsNeeded || startFailuresNow != startFailuresAtOn
    /** Silence (nothing matched, or no speech before the timeout) is normal between questions; anything else is a failure. */
    fun startFailure(errorCode: Int): Boolean =
        errorCode != SpeechRecognizer.ERROR_NO_MATCH && errorCode != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
    /**
     * While paused nothing said is a command or a question, so ambient speech is not sent as a question; only "resume"
     * (and the voice switch) is heard. Hands-free keeps listening for it, or a pause by voice could only be ended by a tap.
     */
    fun pausedCommandAllowed(command: VoiceCommand?, paused: Boolean): Boolean =
        !paused || command == VoiceCommand.Resume || command == VoiceCommand.Mute || command == VoiceCommand.Unmute
}

internal class ListenSession {
    private var generation = 0L
    fun start(): Long = ++generation
    fun active(token: Long) = generation == token
    fun invalidate() { generation++ }
}

/** Hands-free as a screen sees it: whether it is on, the switch, and whether the recognizer keeps audio on the phone. */
class HandsFreeState(val on: Boolean, val toggle: () -> Unit, val onDevice: Boolean)

/**
 * Hands-free: the microphone opens again by itself after each utterance, once the answer has been spoken and nothing is
 * pending, so the technician never touches the phone between questions. Mirrors the web app's `useHandsFree`. [busy] is
 * the screen's pending request. [paused] is not a gate: hands-free listens through a pause so "resume" is heard; the
 * screen's command handler drops everything else.
 * Off again, with one spoken line, when the microphone is blocked or fails to start.
 */
@Composable
fun rememberHandsFree(voice: VoiceInputState, speech: SpeechOutput, busy: Boolean, paused: Boolean): HandsFreeState {
    var on by remember { mutableStateOf(false) }
    // A recognizer that refuses to start leaves `listening` as it was, so that refusal is watched by count. Not saved:
    // the count itself starts again with the recognizer after a rotation.
    var failuresAtOn by remember { mutableStateOf(voice.startFailures) }
    // The engine reports its progress off the main thread, so the speaker's flag is sampled rather than observed.
    val speaking by androidx.compose.runtime.produceState(speech.speaking, speech) { while (true) { value = speech.speaking; kotlinx.coroutines.delay(250) } }
    val current by rememberUpdatedState(voice)
    var started by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleStartEffect(Unit) {
        started = true
        onStopOrDispose {
            started = false
            current.abort()
            on = false
        }
    }
    LaunchedEffect(started, on, voice.supported, voice.listening, speaking, busy, paused, voice.settingsNeeded, voice.startFailures) {
        if (!started || !on) return@LaunchedEffect
        if (HandsFreePolicy.shouldStop(voice.settingsNeeded, voice.startFailures, failuresAtOn)) {
            on = false
            speech.speak("Hands-free is off: the microphone could not start.", force = true)
            return@LaunchedEffect
        }
        if (!HandsFreePolicy.shouldListen(on, voice.supported, voice.listening, speaking, busy, started)) return@LaunchedEffect
        // A short settle, so a listen does not open on the tail of a line; a change meanwhile cancels it.
        kotlinx.coroutines.delay(400)
        current.start()
    }
    // Leaving the screen ends the effect above with it, so the listen the voice input cancels on dispose is not reopened.
    return HandsFreeState(on, toggle = {
        // Off ends a listen that is open right now, so nothing said after the tap is taken as a command.
        if (on) current.abort()
        if (!on) failuresAtOn = current.startFailures
        on = !on
    }, onDevice = voice.onDevice)
}

/**
 * Meta speech input through the configured API. Output still uses Android TextToSpeech.
 */
@Composable
fun rememberVoiceInput(speech: SpeechOutput? = null, onFinal: (String) -> Unit): VoiceInputState {
    val context = LocalContext.current
    val hold = remember(speech) { ListeningHold(speech) }
    DisposableEffect(hold) { onDispose { hold.cancel() } }
    val deliver by rememberUpdatedState(onFinal)
    var listening by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var startFailures by remember { mutableStateOf(0) }
    var settingsNeeded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // Whether Android was still willing to explain the request when it was made; compared with after (see deniedForGood).
    var rationaleBefore by remember { mutableStateOf(false) }
    // Back from Settings with the microphone allowed: the Settings hint and its error go, the mic button works again.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        if (settingsNeeded && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { settingsNeeded = false; error = null }
        onPauseOrDispose { }
    }
    val supported = true
    val onDevice = false
    val dependencies = remember(context) { dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, VoiceDependencies::class.java) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var transcribing by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var stopSignal by remember { mutableStateOf(java.util.concurrent.atomic.AtomicBoolean(false)) }
    val session = remember { ListenSession() }
    var permissionToken by remember { mutableStateOf<Long?>(null) }
    // Releases only the microphone and the upload. Speech keeps its queue: an answer being read is not this listen's to stop.
    // The screen closing is the one case that silences speech, in the hold's own dispose effect above.
    val cancel = {
        session.invalidate(); permissionToken = null; stopSignal.set(true); operation?.cancel(); operation = null
        val wasListening = listening
        listening = false; transcribing = false; heard = ""
        if (wasListening) hold.ended()
    }
    val currentCancel by rememberUpdatedState(cancel)
    DisposableEffect(session) { onDispose { currentCancel() } }
    androidx.lifecycle.compose.LifecycleStartEffect(Unit) { onStopOrDispose { currentCancel() } }
    val begin = {
        if (operation?.isActive != true) {
            val token = session.start()
            val stop = java.util.concurrent.atomic.AtomicBoolean(false); stopSignal = stop
            heard = ""; error = null; listening = true; transcribing = false; hold.started()
            operation = scope.launch {
                try {
                    val audio = dependencies.recorder().recordUtterance(stop)
                    try {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        transcribing = true; heard = "Transcribing with Meta…"
                        val result = com.lensqi.fieldops.data.api.apiCall {
                            dependencies.api().transcribeVoice(okhttp3.MultipartBody.Part.createFormData("audio", "speech.wav", audio.asRequestBody("audio/wav".toMediaType())))
                        }
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (session.active(token)) {
                            when (result) {
                                is com.lensqi.fieldops.data.api.ApiOutcome.Ok -> {
                                    listening = false
                                    hold.result(result.value.transcript) { heard = it; deliver(it) }
                                }
                                is com.lensqi.fieldops.data.api.ApiOutcome.Rejected -> {
                                    error = if (result.code == "no_speech") NOTHING_HEARD else result.message
                                    if (result.code != "no_speech") startFailures++
                                }
                                is com.lensqi.fieldops.data.api.ApiOutcome.Unreachable -> { error = "Meta transcription requires a connection. Try again or type instead."; startFailures++ }
                            }
                        }
                    } finally { audio.delete() }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: NoVoiceDetected) { if (session.active(token)) error = NOTHING_HEARD }
                catch (e: Exception) { if (session.active(token)) { error = "Voice input failed. Try again or type instead."; startFailures++ } }
                finally { if (session.active(token)) { listening = false; transcribing = false; hold.ended(); operation = null } }
            }
        }
        Unit
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val token = permissionToken
        permissionToken = null
        if (token == null || !session.active(token)) return@rememberLauncherForActivityResult
        if (granted) { settingsNeeded = false; begin() }
        else {
            // Sticky, and remembered across visits: once Android stops asking, every later tap is refused with no rationale
            // either side, which must not hide the Settings way in; the rationale it showed once is kept on the device.
            val after = com.lensqi.fieldops.ui.common.rationaleShown(context, Manifest.permission.RECORD_AUDIO)
            com.lensqi.fieldops.ui.common.noteRationale(context, Manifest.permission.RECORD_AUDIO)
            settingsNeeded = settingsNeeded || com.lensqi.fieldops.ui.common.deniedForGood(rationaleBefore, after, com.lensqi.fieldops.ui.common.rationaleEverShown(context, Manifest.permission.RECORD_AUDIO))
            error = if (settingsNeeded) "The microphone is off for LensQi and Android will not ask again. Allow it in Settings, or keep typing." else "Microphone permission is needed for voice."
            // A refusal is a start that failed: hands-free must not ask again every half second.
            startFailures++
        }
    }
    return VoiceInputState(listening, heard, error, supported,
        start = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
            // Android answers a second request made while the dialog is up with an instant refusal, which would read as the
            // technician's: it would end hands-free and could send them to Settings. Hands-free retries while the dialog waits.
            else if (permissionToken == null) {
                rationaleBefore = com.lensqi.fieldops.ui.common.rationaleShown(context, Manifest.permission.RECORD_AUDIO)
                com.lensqi.fieldops.ui.common.noteRationale(context, Manifest.permission.RECORD_AUDIO)
                permissionToken = session.start()
                permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        stop = { if (transcribing) cancel() else stopSignal.set(true) },
        settingsNeeded = settingsNeeded, openSettings = { com.lensqi.fieldops.ui.common.openAppSettings(context) },
        onDevice = onDevice,
        startFailures = startFailures,
        // Hands-free ending hides nothing it kept quiet: an old silence must not be said aloud once the mode is off.
        abort = { if (error == NOTHING_HEARD) error = null; cancel() },
    )
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface VoiceDependencies {
    fun api(): com.lensqi.fieldops.data.api.FieldOpsApi
    fun recorder(): ClaimAudioRecorder
}
