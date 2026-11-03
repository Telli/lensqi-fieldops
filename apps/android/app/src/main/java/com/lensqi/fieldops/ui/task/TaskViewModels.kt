package com.lensqi.fieldops.ui.task

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lensqi.fieldops.data.api.AbandonRequest
import com.lensqi.fieldops.data.api.AcceptRequest
import com.lensqi.fieldops.data.api.ApiOutcome
import com.lensqi.fieldops.data.api.FieldOpsApi
import com.lensqi.fieldops.data.api.InspectionView
import com.lensqi.fieldops.data.api.TaskMode
import com.lensqi.fieldops.data.api.TaskSummary
import com.lensqi.fieldops.data.api.TaskView
import com.lensqi.fieldops.data.api.apiCall
import com.lensqi.fieldops.data.api.retryable
import com.lensqi.fieldops.data.queue.CommandKind
import com.lensqi.fieldops.data.queue.StepCheckQueue
import com.lensqi.fieldops.data.queue.CommandState
import com.lensqi.fieldops.data.queue.PendingCommand
import com.lensqi.fieldops.domain.live.LiveResponse
import com.lensqi.fieldops.ui.common.friendly
import com.lensqi.fieldops.voice.SpeechOutput
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID
import javax.inject.Inject

private fun text(value: String) = value.toRequestBody("text/plain".toMediaType())
/** The server's EvidenceFileStore.MaxImageBytes. */
internal const val MAX_UPLOAD_BYTES = 12L * 1024 * 1024
private fun jpeg(name: String, bytes: ByteArray) = MultipartBody.Part.createFormData(name, "$name.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
private fun key() = UUID.randomUUID().toString()

private fun <T> ApiOutcome<T>.errorText(): String? = when (this) {
    is ApiOutcome.Ok -> null
    is ApiOutcome.Rejected -> friendly(code, message)
    is ApiOutcome.Unreachable -> friendly("offline", message)
}

data class TaskListUi(val tasks: List<TaskSummary> = emptyList(), val creating: Boolean = false, val error: String? = null)

/** The task list and starting a new task: the server writes and checks the plan; the id always comes from the API. */
@HiltViewModel
class TaskListViewModel @Inject constructor(private val api: FieldOpsApi, private val settings: com.lensqi.fieldops.data.settings.SettingsStore) : ViewModel() {
    private val _state = MutableStateFlow(TaskListUi())
    val state: StateFlow<TaskListUi> = _state

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        (apiCall { api.tasks() } as? ApiOutcome.Ok)?.value?.let { list -> _state.update { it.copy(tasks = list) } }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Free-form help: straight to the camera, no goal or plan. */
    fun justAsk(onCreated: (String) -> Unit) = create(TaskMode.Freeform, "", null, null, null, emptyList(), onCreated)

    // A retried create reuses its key so the server returns the task it already made instead of making another.
    private val createKeys = mutableMapOf<String, String>()

    fun create(mode: TaskMode, goal: String, details: String?, fixtureId: String?, assetId: String?, manuals: List<File>, onCreated: (String) -> Unit) = viewModelScope.launch {
        _state.update { it.copy(creating = true, error = null) }
        val parts = manuals.take(3).map { MultipartBody.Part.createFormData("manual", it.name, it.asRequestBody("image/jpeg".toMediaType())) }
        val action = "${mode.name}:${goal.trim()}"
        val k = createKeys.getOrPut(action) { key() }
        // The person's height and tools (Profile) go with the goal, so the plan fits who is doing the job.
        val person = settings.person.first()
        val r = apiCall { api.createTask(k, text(mode.name), text(goal.trim()), details?.takeIf { it.isNotBlank() }?.let(::text), fixtureId?.let(::text), assetId?.let(::text), parts,
            person.heightCm?.let { text(it.toString()) }, person.tools.map(::text)) }
        if (!r.retryable) createKeys.remove(action)
        _state.update { it.copy(creating = false, error = r.errorText()) }
        if (r is ApiOutcome.Ok) { refresh(); onCreated(r.value.task.taskId) }
    }
}

data class TaskUi(
    val task: TaskView? = null,
    val busy: String? = null,
    val error: String? = null,
    val answer: Pair<String, String>? = null,
    /** The FieldProof inspection a technician task is linked to, for its stage and live or simulated outcome. */
    val linked: InspectionView? = null,
    /** Step-check photos saved on the phone, waiting for the network. */
    val queuedChecks: Int = 0,
    val refusedChecks: List<PendingCommand> = emptyList(),
)

/** One task: plan, steps, questions and photo checks. The AI coaches and suggests; the person decides when a step is done. */
@HiltViewModel
class TaskViewModel @Inject constructor(saved: SavedStateHandle, private val api: FieldOpsApi, val speaker: SpeechOutput, private val queue: StepCheckQueue,
    private val spoken: SpokenChecks = SpokenChecks()) : ViewModel() {
    val taskId: String = checkNotNull(saved["taskId"])
    /** Decodes and shrinks a shutter photo; Android's bitmap decoder in the app, a stand-in in JVM tests. */
    internal var reduce: (File, Int) -> ByteArray? = ::downscaledJpeg
    private val _state = MutableStateFlow(TaskUi())
    val state: StateFlow<TaskUi> = _state

    init {
        refresh()
        // Queued step checks: show how many wait, refresh when one is checked, and report a refused one once.
        viewModelScope.launch {
            var waiting = 0
            queue.observe(taskId).collect { all ->
                val checks = all.filter { it.kind == CommandKind.StepCheckPhoto }
                val pending = checks.count { it.state == CommandState.Pending }
                if (pending < waiting) refresh()
                waiting = pending
                _state.update { it.copy(queuedChecks = pending, refusedChecks = checks.filter { c -> c.state == CommandState.Rejected }) }
                checks.filter { it.state == CommandState.Rejected }.forEach { c ->
                    _state.update { it.copy(error = friendly(c.errorCode ?: "unknown", c.errorMessage.orEmpty())) }
                    // Keep refused originals until the user explicitly removes or retries them.
                }
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        val r = apiCall { api.task(taskId) }
        _state.update { it.copy(task = (r as? ApiOutcome.Ok)?.value ?: it.task, error = r.errorText()) }
        (r as? ApiOutcome.Ok)?.value?.let(::speakNewChecks)
        _state.value.task?.inspectionId?.let { id -> (apiCall { api.inspection(id) } as? ApiOutcome.Ok)?.value?.let { i -> _state.update { it.copy(linked = i) } } }
    }

    /**
     * Check photos whose verdict has been spoken. A check saved offline is spoken when its verdict first appears after a
     * sync, once; the checks already in the record when the task opened are not read out again.
     */
    private var seeded = false
    private fun speakNewChecks(task: TaskView) {
        val checks = task.plan?.steps?.mapNotNull { it.lastCheck }.orEmpty()
        if (!seeded) { spoken.seed(checks.map { it.photoId }); seeded = true; return }
        checks.filter { spoken.firstTime(it.photoId) }.forEach { c -> speaker.speak("${verdictText(c.verdict)}. ${c.reason}", force = true) }
    }

    // One idempotency key per pending action (RetryKeys): a retry after a lost response, a server error or a failed model
    // call replays the server's outcome (or reuses the photo it saved) instead of acting twice. A refusal drops the key.
    private val keys = com.lensqi.fieldops.data.api.RetryKeys(saved)
    private fun keyFor(action: String) = keys.keyFor(action)
    private fun <T> settle(action: String, r: ApiOutcome<T>) {
        keys.settle(action, r)
        // A refusal means the server's state moved on (for example the step was already done): show it.
        if (r is ApiOutcome.Rejected) refresh()
    }

    private fun act(label: String, action: String, block: suspend (String) -> TaskView, after: (TaskView) -> Unit = {}) = viewModelScope.launch {
        _state.update { it.copy(busy = label, error = null) }
        val r = apiCall { block(keyFor(action)) }
        _state.update { it.copy(busy = null, task = (r as? ApiOutcome.Ok)?.value ?: it.task, error = r.errorText()) }
        settle(action, r)
        if (r is ApiOutcome.Ok) after(r.value)
    }

    fun replan() = act("plan", "plan", { k -> api.replan(taskId, k) })
    fun accept(acknowledged: Boolean, onStarted: () -> Unit) = act("accept", "accept", { k -> api.acceptPlan(taskId, k, AcceptRequest(acknowledged)) }) { onStarted() }
    fun complete(stepId: String, onMoved: (TaskView) -> Unit) = act("move", "complete:$stepId", { k -> api.stepAction(taskId, stepId, "complete", k) }, onMoved)
    fun skip(stepId: String, onMoved: (TaskView) -> Unit) = act("move", "skip:$stepId", { k -> api.stepAction(taskId, stepId, "skip", k) }, onMoved)
    /** Skips an access step the person can reach without, keeping the measured reason in the task's record. */
    fun skipNotNeeded(stepId: String, advice: ReachAdvice) = act("move", "skip:$stepId", { k ->
        api.skipStep(taskId, stepId, k, com.lensqi.fieldops.data.api.SkipRequest(advice.text.take(200), advice.partHeightCm, advice.reachCm)) })

    /** What ARCore measured on the step screen; it goes with live frames and questions so the coach can use real numbers. */
    @Volatile var measured: List<com.lensqi.fieldops.domain.live.LiveObject> = emptyList()
    /** The part confirmed on the camera, kept while the step stays the same. */
    val spatialTarget = com.lensqi.fieldops.ui.camera.TargetMemory()
    fun clearError() = _state.update { it.copy(error = null) }

    /**
     * A full-resolution step photo: kept as the task's record, then the AI's suggested verdict is shown and spoken.
     * [origin] is the capture device ("Phone", "Glasses" or "MockWearables"); the server keeps it with the photo.
     */
    fun check(stepId: String, photo: File, origin: String = "Phone") = viewModelScope.launch {
        _state.update { it.copy(busy = "check", error = null) }
        // Preserve the original evidence; model input preparation belongs to the server. A photo over the server's upload
        // limit would be refused outright, so it goes as an upright copy of at most 2400 px instead.
        val bytes = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { if (photo.length() <= MAX_UPLOAD_BYTES) photo.readBytes() else reduce(photo, 2400) }
        } catch (e: java.io.IOException) { null }
        if (bytes == null) { _state.update { it.copy(busy = null, error = "That photo couldn't be read. Take it again.") }; return@launch }
        val part = jpeg("image", bytes)
        val action = "check:$stepId:${key()}"
        val key = keyFor(action)
        val r = apiCall { api.checkStep(taskId, stepId, key, part, text(origin)) }
        if (r.retryable) {
            // GT-R09.5: offline, the photo waits on the phone under the same key and is checked when the network returns.
            val saved = try { queue.enqueueStepCheck(taskId, stepId, key, bytes, origin); true }
                catch (e: java.io.IOException) { false } catch (e: com.lensqi.fieldops.data.queue.UnsupportedImageException) { false }
            keys.drop(action)
            _state.update { it.copy(busy = null, error = if (saved) "The photo is saved on the phone and will be checked when the service is available." else friendly("local_save_failed", "")) }
            return@launch
        }
        _state.update { it.copy(busy = null, task = (r as? ApiOutcome.Ok)?.value?.task ?: it.task, error = r.errorText()) }
        settle(action, r)
        (r as? ApiOutcome.Ok)?.value?.check?.let { c -> spoken.firstTime(c.photoId); speaker.speak("${verdictText(c.verdict)}. ${c.reason}", force = true) }
    }

    /** A question by voice or text, with the latest camera frame when live coaching has one. The answer is spoken. */
    fun ask(question: String, frame: ByteArray?) = viewModelScope.launch {
        if (question.isBlank()) return@launch
        _state.update { it.copy(busy = "ask", error = null) }
        val action = "ask:${question.trim()}:${frame?.let { com.lensqi.fieldops.data.queue.CommandQueue.sha256(it) }}"
        val r = apiCall { api.ask(taskId, keyFor(action), text(question.trim()), frame?.let { jpeg("image", it) }, measuredJson(measured)?.let(::text)) }
        settle(action, r)
        val answer = (r as? ApiOutcome.Ok)?.value?.answer
        _state.update { it.copy(busy = null, error = r.errorText(), answer = answer?.let { a -> question.trim() to a } ?: it.answer) }
        answer?.let { speaker.speak(it, force = true) }
        // The answer is now part of the task's record; reload it so conversation views show it.
        if (answer != null) refresh()
    }

    fun retryCheck(command: PendingCommand) = viewModelScope.launch { queue.retry(command) }
    fun removeCheck(command: PendingCommand) = viewModelScope.launch { queue.dismiss(command) }

    fun clearAnswer() = _state.update { it.copy(answer = null) }

    /** Ends free-form help. */
    fun finish(onDone: () -> Unit) = act("finish", "finish", { k -> api.finishTask(taskId, k) }) { onDone() }
    /** Stops a task that is not finished; the record is kept and no more steps are accepted. */
    fun abandon(onStopped: () -> Unit) = act("stop", "abandon", { k -> api.abandonTask(taskId, k, AbandonRequest("Stopped by the user")) }) { onStopped() }

    /** Asks about a full-resolution shutter photo, reduced to at most 1600 px on its long side before upload. */
    fun askAboutPhoto(question: String, photo: File) = viewModelScope.launch {
        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { reduce(photo, 1600) }
        ask(question, bytes)
    }

    fun linkInspection(onLinked: (String) -> Unit) = viewModelScope.launch {
        _state.update { it.copy(busy = "link", error = null) }
        val r = apiCall { api.linkInspection(taskId, key()) }
        _state.update { it.copy(busy = null, task = (r as? ApiOutcome.Ok)?.value?.task ?: it.task, error = r.errorText()) }
        if (r is ApiOutcome.Ok) onLinked(r.value.inspectionId)
    }

    suspend fun analyzeLiveFrame(kind: String, bytes: ByteArray, frameId: String): LiveResponse =
        api.taskLiveFrame(taskId, kind, jpeg("image", bytes), text(frameId), measuredJson(measured)?.let(::text))
}

/** A JPEG of the photo, upright (EXIF rotation applied) and at most [maxSide] pixels on its long side. */
internal fun downscaledJpeg(file: File, maxSide: Int): ByteArray? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val decoded = android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    // Decoding drops the camera's EXIF rotation; apply it so the model sees the photo upright.
    val degrees = when (android.media.ExifInterface(file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    val fit = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
    val matrix = android.graphics.Matrix().apply { postScale(fit, fit); postRotate(degrees) }
    val bitmap = if (degrees == 0f && fit == 1f) decoded else android.graphics.Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
    return java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it); bitmap.recycle() }.toByteArray()
}
