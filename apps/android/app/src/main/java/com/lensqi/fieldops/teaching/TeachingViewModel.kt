package com.lensqi.fieldops.teaching

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.video.AudioConfig
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.lensqi.fieldops.data.api.FieldOpsApi
import com.lensqi.fieldops.data.api.TaskView
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.io.File
import java.io.OutputStream
import java.time.Instant
import javax.inject.Inject

data class TeachingUi(
    val records: List<TeachingRecord> = emptyList(), val selected: TeachingRecord? = null,
    val events: List<TeachingEvent> = emptyList(), val reviewed: Boolean = false,
    val task: TaskView? = null, val loading: Boolean = true, val busy: Boolean = false,
    val error: String? = null, val message: String? = null, val seconds: Long = 0,
)

@HiltViewModel
class TeachingViewModel @Inject constructor(app: Application, saved: SavedStateHandle, private val api: FieldOpsApi) : AndroidViewModel(app) {
    private val taskId = saved.get<String>("taskId")?.takeIf { it.isNotBlank() }
    val store = TeachingStore(File(app.filesDir, "teaching"))
    private val mutable = MutableStateFlow(TeachingUi())
    val state = mutable.asStateFlow()
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var recording: Recording? = null
    @Volatile private var activeId: String? = null
    private var foreground = false
    // Main thread only. The first stop request wins; a stop the app makes (not the user) marks the recording Interrupted.
    private var stopReason: String? = null
    private var stopInterrupted = false
    /** A stop asked for while the recording was still starting (recording not yet assigned); applied once it is. */
    private var pendingStop: PendingStop? = null
    /** From a start being accepted until CameraX is recording, so a stop in that window is kept rather than lost. */
    @Volatile private var starting = false
    private class PendingStop(val reason: String?)
    private var lastSampleNs = 0L
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (command in commands) try { command() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                mutable.update { it.copy(busy = false, loading = false, error = e.message ?: "Could not save the recording.") }
            }
        }
        work { store.recover { runCatching { inspectTeachingVideo(it) }.getOrNull() }; refresh(); mutable.update { it.copy(loading = false) } }
        if (taskId != null) viewModelScope.launch {
            try { val task = withTimeout(10_000) { api.task(taskId) }; mutable.update { it.copy(task = task) } }
            catch (e: CancellationException) { if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e; unavailableContext() }
            catch (_: Exception) { unavailableContext() }
        }
    }
    private fun unavailableContext() { mutable.update { it.copy(message = "Task context is unavailable. You can still record locally.") } }
    private fun work(block: suspend () -> Unit) { commands.trySend(block) }
    private fun refresh(id: String? = mutable.value.selected?.id, records: Boolean = true) {
        mutable.update { it.copy(records = if (records) store.list() else it.records, selected = id?.let(store::read), events = id?.let(store::events).orEmpty(), reviewed = id?.let(store::reviewed) ?: false) }
    }
    fun foregroundChanged(value: Boolean) { foreground = value; if (!value) stop("App left the foreground.") }
    fun clearError() { mutable.update { it.copy(error = null, message = null) } }
    fun select(id: String?) = work { refresh(id) }

    fun start(controller: LifecycleCameraController, goal: String, equipment: String, demonstrator: String, permission: Boolean, training: Boolean) {
        if (mutable.value.busy || activeId != null || starting) return
        // Accepted now, before any asynchronous work: a stop asked from here on belongs to this recording, and the previous
        // recording's stop state must not swallow it.
        starting = true; pendingStop = null; stopReason = null; stopInterrupted = false
        mutable.update { it.copy(busy = true, error = null, message = null) }
        val now = Instant.now().toString()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        work {
            // A failed pre-check (free space, the sessions kept) ends this start: the next tap must be able to start again.
            val record = try {
                val root = File(getApplication<Application>().filesDir, "teaching")
                require(root.usableSpace > 300L * 1024 * 1024) { "Free at least 300 MB before recording." }
                require(root.walkTopDown().filter { it.isFile }.sumOf { it.length() } < 768L * 1024 * 1024) { "Export and delete older sessions before recording more." }
                store.create(goal, equipment, demonstrator, TeachingConsent(permission, training, now), now, elapsed,
                    "${if (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone") || Build.MODEL.contains("Emulator")) "Emulator" else "Phone camera"} · ${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}", taskId)
            } catch (e: Exception) { starting = false; pendingStop = null; throw e }
            activeId = record.id; lastSampleNs = 0
            try {
                // Snapshot the visible plan immediately; live frames, personal profiles and server audio are not uploaded.
                mutable.value.task?.let { task -> store.context(record.id, buildJsonObject {
                    put("capturedAt", now); put("source", "Server task snapshot; AI plans retain their sourceNote")
                    put("task", json.encodeToJsonElement(task.copy(person = null)))
                }.toString()) }
                refresh(record.id)
                withContext(Dispatchers.Main) {
                    check(foreground) { "The recorder is no longer in the foreground." }
                    recording = controller.startRecording(FileOutputOptions.Builder(store.video(record.id))
                        .setFileSizeLimit(256L * 1024 * 1024).setDurationLimitMillis(10 * 60 * 1000L).build(),
                        AudioConfig.AUDIO_DISABLED, ContextCompat.getMainExecutor(getApplication()), ::onVideoEvent)
                    starting = false
                    // A stop asked for during start-up (the app left the foreground, the camera closed, the user) applies now.
                    pendingStop?.let { pendingStop = null; stop(it.reason) }
                }
            } catch (e: CancellationException) { starting = false; throw e }
            catch (_: Exception) {
                starting = false; activeId = null; pendingStop = null
                store.save(record.copy(state = RecordingState.Failed, stopReason = "Recording could not start."))
                refresh(record.id)
                mutable.update { it.copy(busy = false, error = "Recording could not start. Check free space and camera access, then try again.") }
            }
        }
    }

    private fun onVideoEvent(event: VideoRecordEvent) {
        val id = activeId ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        val duration = event.recordingStats.recordedDurationNanos
        when (event) {
            is VideoRecordEvent.Start -> work {
                store.save(store.read(id).copy(state = RecordingState.Recording))
                store.append(id, TeachingKind.RecordingStarted, now, TeachingSource.CaptureSystem, durationNs = duration)
                refresh(id); mutable.update { it.copy(busy = false, seconds = 0) }
            }
            is VideoRecordEvent.Status -> {
                mutable.update { it.copy(seconds = duration / 1_000_000_000) }
                if (now - lastSampleNs > 5_000_000_000) { lastSampleNs = now; work { store.append(id, TeachingKind.ClockSample, now, TeachingSource.CaptureSystem, durationNs = duration) } }
            }
            is VideoRecordEvent.Finalize -> {
                recording = null
                mutable.update { it.copy(busy = true) }
                // The limits end a valid video, but the demonstration may be cut short, so it is not Complete.
                val limit = when (event.error) {
                    VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED -> "Stopped at the 10-minute limit; the demonstration may be cut short."
                    VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> "Stopped at the 256 MB limit; the demonstration may be cut short."
                    else -> null
                }
                val failure = if (event.hasError() && limit == null) "CameraX finalization code ${event.error}" else null
                val reason = failure ?: limit ?: stopReason ?: "Recording ended without a stop request."
                val interrupted = failure != null || limit != null || stopReason == null || stopInterrupted
                work {
                    val video = runCatching { inspectTeachingVideo(store.folder(id)) }.getOrNull()
                    store.finish(id, now, duration, reason, interrupted, video)
                    activeId = null
                    refresh(id)
                    mutable.update { it.copy(busy = false, message = if (video == null) "The video could not be finalized. The partial recording is kept on this phone." else "Saved on this phone. Review the video and annotations before exporting.") }
                    fetchContext(id)
                }
            }
            else -> Unit
        }
    }

    /** With no reason the user chose to stop; the app passes a reason when it stops on leaving the foreground or closing the camera. */
    fun stop(reason: String? = null) {
        // Still starting (no record yet, or no recording object yet): the first request is kept and applied once recording runs.
        if (starting && recording == null) { if (pendingStop == null) pendingStop = PendingStop(reason); return }
        val id = activeId ?: return
        if (stopReason != null) return
        // Still starting: the recording object does not exist yet, so the request is kept and applied once it does.
        if (recording == null) { if (pendingStop == null) pendingStop = PendingStop(reason); return }
        stopReason = reason ?: USER_STOP
        stopInterrupted = reason != null
        val now = SystemClock.elapsedRealtimeNanos()
        mutable.update { it.copy(busy = true) }
        work {
            TeachingStore.openAction(store.events(id))?.let { store.append(id, TeachingKind.ActionEnd, now, TeachingSource.CaptureSystem, text = "Recording stopped; action may be incomplete.", refersTo = it.sequence) }
            store.append(id, TeachingKind.StopRequested, now, TeachingSource.CaptureSystem, reason ?: USER_STOP)
        }
        recording?.stop()
    }

    fun annotate(kind: TeachingKind, text: String, stepId: String? = null, refersTo: Int? = null, source: TeachingSource = TeachingSource.User, actionType: String? = null, objectName: String? = null) {
        val id = mutable.value.selected?.id ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        work { store.append(id, kind, now, source, text.trim(), stepId, refersTo, actionType = actionType, objectName = objectName); refresh(id, records = false) }
    }
    fun delete(id: String) = work { require(id != activeId); store.delete(id); refresh(null) }
    fun export(open: () -> OutputStream?) {
        val id = mutable.value.selected?.id ?: return
        mutable.update { it.copy(busy = true, error = null) }
        work {
            checkNotNull(open()) { "Could not open the export destination." }.use { store.export(id, it) }
            mutable.update { it.copy(busy = false, message = "Export saved. The ZIP includes video, annotations, timestamps and provenance.") }
        }
    }

    private suspend fun fetchContext(id: String) {
        val task = mutable.value.task ?: return
        try {
            val context = withTimeout(10_000) {
                buildJsonObject {
                    put("capturedAt", Instant.now().toString()); put("source", "Server records; timestamps and diagnostic modes retained. May include records from before this demonstration.")
                    put("task", json.encodeToJsonElement(task.copy(person = null)))
                    put("taskEvents", JsonArray(api.taskEvents(task.taskId).map { JsonObject(it - "person") }))
                    task.inspectionId?.let { put("inspectionEvents", JsonArray(api.events(it))) }
                }
            }
            store.context(id, context.toString())
        } catch (e: CancellationException) { if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e }
        catch (_: Exception) { /* Local video remains useful offline; only the initial snapshot is available. */ }
    }

    private companion object { const val USER_STOP = "User stopped recording." }

    override fun onCleared() { recording?.stop(); recording?.close(); commands.close(); super.onCleared() }
}
