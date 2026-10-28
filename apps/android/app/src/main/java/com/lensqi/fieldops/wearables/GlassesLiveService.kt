package com.lensqi.fieldops.wearables

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lensqi.fieldops.data.api.FieldOpsApi
import com.lensqi.fieldops.ui.MainActivity
import com.lensqi.fieldops.ui.camera.LiveCameraSession
import com.lensqi.fieldops.ui.camera.LiveCameraState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

/** Explicitly started while visible. Owns live inference and speech even with the phone locked. */
@AndroidEntryPoint
class GlassesLiveService : Service() {
    @Inject lateinit var source: GlassesSource
    @Inject lateinit var api: FieldOpsApi
    @Inject lateinit var store: GlassesLiveStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionLock = Any()
    private var session: LiveCameraSession? = null
    private var feedbackJob: Job? = null
    @Volatile private var running = false
    @Volatile private var lastFrameAt = 0L
    private var speaker: TextToSpeech? = null
    private var speechReady = false
    private var speechGate = SpokenFeedbackGate()
    private var lastHud: Pair<String, String>? = null
    private var lastHudAt = 0L
    private var wake: PowerManager.WakeLock? = null
    private var keepPreviewOnStop = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Live glasses assistance", NotificationManager.IMPORTANCE_LOW))
        speaker = TextToSpeech(applicationContext) { result ->
            speechReady = result == TextToSpeech.SUCCESS
            if (!speechReady) store.mutable.update { it.copy(audioNote = "Speech unavailable. Read guidance on the phone or glasses display.") }
            else speaker?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) { keepPreviewOnStop = true; stopSelf(); return START_NOT_STICKY }
        if (intent?.action == STOP) { source.stopStreaming(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action == MUTE) { if (running) toggleMute() else stopSelf(); return START_NOT_STICKY }
        val id = intent?.getStringExtra(INSPECTION) ?: run { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        // Promotion precedes any model work. The user can stop from the notification without unlocking the app.
        try { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) }
        catch (_: Exception) { source.reportError("Live assistance could not stay active. Check Bluetooth permission and try again."); stopSelf(); return START_NOT_STICKY }
        if (!source.state.value.streaming) { source.reportError("Start the glasses camera before live assistance."); stopSelf(); return START_NOT_STICKY }
        running = true; lastFrameAt = SystemClock.elapsedRealtime()
        store.mutable.value = GlassesLiveState(inspectionId = id, enabled = true, audioNote = store.state.value.audioNote)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LensQi:GlassesLive").apply { acquire(60_000) }
        source.setFrameConsumer { bitmap ->
            if (running && source.state.value.streaming) {
                lastFrameAt = SystemClock.elapsedRealtime()
                val live = synchronized(sessionLock) {
                    if (!running) return@setFrameConsumer
                    session ?: LiveCameraSession { kind, bytes, frame ->
                        api.liveFrame(id, kind, MultipartBody.Part.createFormData("image", "glasses-preview.jpg", bytes.toRequestBody("image/jpeg".toMediaType())),
                            frame.toRequestBody("text/plain".toMediaType()))
                    }.also { created ->
                        session = created
                        feedbackJob = scope.launch { created.state.collect { value ->
                            if (running && session === created) store.mutable.update { it.copy(feedback = value) }
                        } }
                    }
                }
                live.frame(bitmap)
            }
        }
        scope.launch {
            source.state.collect { state ->
                if (state.streaming && session == null) lastFrameAt = SystemClock.elapsedRealtime()
                if (!state.streaming) {
                    clearSession(if (state.phase == GlassesPhase.Paused) "Camera paused on glasses" else "Glasses camera disconnected")
                    if (state.phase != GlassesPhase.Paused && state.phase != GlassesPhase.Starting) stopSelf()
                }
            }
        }
        scope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val age = now - lastFrameAt
                if (age > 2500 && source.state.value.streaming) clearSession("Waiting for fresh glasses frames")
                // A stalled stream may recover, but must never leave old boxes/advice or an unattended session running.
                if (age > 30_000 && source.state.value.phase != GlassesPhase.Paused) {
                    source.reportError("The glasses stopped sending frames. Start the camera again.")
                    source.stopStreaming(); stopSelf(); break
                }
                if (age < 2500 && wake?.isHeld != true) wake?.acquire(60_000)
                deliverFeedback(now)
                delay(500)
            }
        }
        return START_NOT_STICKY
    }

    private fun clearSession(message: String) {
        synchronized(sessionLock) { session?.close(); session = null; feedbackJob?.cancel(); feedbackJob = null }
        store.mutable.update { it.copy(feedback = LiveCameraState(status = message, recommendation = message)) }
    }

    private fun deliverFeedback(now: Long) {
        val state = store.state.value
        val f = state.feedback
        val badge = if (source.state.value.mode == GlassesMode.Mock) "Mock glasses · AI inferred" else "Live glasses · AI inferred"
        val hud = f.recommendation to "$badge · ${f.status}"
        if (hud != lastHud && now - lastHudAt >= 2000) { source.showHud(hud.first, hud.second); lastHud = hud; lastHudAt = now }
        if (!state.muted && speechReady) speechGate.next(f.recommendation, now)?.let { text ->
            speaker?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "glasses-live-guidance")
        }
    }

    private fun toggleMute() {
        store.mutable.update { it.copy(muted = !it.muted) }
        speaker?.stop(); speechGate = SpokenFeedbackGate()
        if (running) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(name: String) = PendingIntent.getService(this, name.hashCode(), Intent(this, GlassesLiveService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("LensQi live glasses assistance").setContentText("Camera analysis active · sampled frames sent to AI")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, if (store.state.value.muted) "Unmute" else "Mute", action(MUTE))
            .addAction(0, "Stop camera", action(STOP)).build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) { source.stopStreaming(); stopSelf() }
    override fun onDestroy() {
        running = false
        source.setFrameConsumer(null)
        clearSession("Live assistance stopped")
        scope.cancel(); speaker?.stop(); speaker?.shutdown()
        wake?.let { if (it.isHeld) it.release() }; wake = null
        if (!keepPreviewOnStop) source.stopStreaming()
        store.mutable.value = GlassesLiveState()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "glasses-live"
        private const val NOTIFICATION_ID = 703
        private const val INSPECTION = "inspection"
        private const val STOP = "com.lensqi.fieldops.STOP_GLASSES"
        private const val PAUSE = "com.lensqi.fieldops.PAUSE_GLASSES"
        private const val MUTE = "com.lensqi.fieldops.MUTE_GLASSES"
        fun start(context: Context, inspectionId: String) = ContextCompat.startForegroundService(context,
            Intent(context, GlassesLiveService::class.java).putExtra(INSPECTION, inspectionId))
        fun pause(context: Context) { context.startService(Intent(context, GlassesLiveService::class.java).setAction(PAUSE)) }
        fun mute(context: Context) { context.startService(Intent(context, GlassesLiveService::class.java).setAction(MUTE)) }
    }
}
