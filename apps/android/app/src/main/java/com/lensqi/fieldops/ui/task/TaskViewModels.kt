package com.lensqi.fieldops.ui.task

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lensqi.fieldops.data.api.AcceptRequest
import com.lensqi.fieldops.data.api.ApiOutcome
import com.lensqi.fieldops.data.api.FieldOpsApi
import com.lensqi.fieldops.data.api.InspectionView
import com.lensqi.fieldops.data.api.TaskMode
import com.lensqi.fieldops.data.api.TaskSummary
import com.lensqi.fieldops.data.api.TaskView
import com.lensqi.fieldops.data.api.apiCall
import com.lensqi.fieldops.domain.live.LiveResponse
import com.lensqi.fieldops.ui.common.friendly
import com.lensqi.fieldops.voice.Speaker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
class TaskListViewModel @Inject constructor(private val api: FieldOpsApi) : ViewModel() {
    private val _state = MutableStateFlow(TaskListUi())
    val state: StateFlow<TaskListUi> = _state

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        (apiCall { api.tasks() } as? ApiOutcome.Ok)?.value?.let { list -> _state.update { it.copy(tasks = list) } }
    }

    /** Free-form help: straight to the camera, no goal or plan. */
    fun justAsk(onCreated: (String) -> Unit) = create(TaskMode.Freeform, "", null, null, null, emptyList(), onCreated)

    fun create(mode: TaskMode, goal: String, details: String?, fixtureId: String?, assetId: String?, manuals: List<File>, onCreated: (String) -> Unit) = viewModelScope.launch {
        _state.update { it.copy(creating = true, error = null) }
        val parts = manuals.take(3).map { MultipartBody.Part.createFormData("manual", it.name, it.asRequestBody("image/jpeg".toMediaType())) }
        val r = apiCall { api.createTask(key(), text(mode.name), text(goal.trim()), details?.takeIf { it.isNotBlank() }?.let(::text), fixtureId?.let(::text), assetId?.let(::text), parts) }
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
)

/** One task: plan, steps, questions and photo checks. The AI coaches and suggests; the person decides when a step is done. */
@HiltViewModel
class TaskViewModel @Inject constructor(saved: SavedStateHandle, private val api: FieldOpsApi, val speaker: Speaker) : ViewModel() {
    val taskId: String = checkNotNull(saved["taskId"])
    private val _state = MutableStateFlow(TaskUi())
    val state: StateFlow<TaskUi> = _state

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        val r = apiCall { api.task(taskId) }
        _state.update { it.copy(task = (r as? ApiOutcome.Ok)?.value ?: it.task, error = r.errorText()) }
        _state.value.task?.inspectionId?.let { id -> (apiCall { api.inspection(id) } as? ApiOutcome.Ok)?.value?.let { i -> _state.update { it.copy(linked = i) } } }
    }

    private fun act(label: String, block: suspend () -> TaskView, after: (TaskView) -> Unit = {}) = viewModelScope.launch {
        _state.update { it.copy(busy = label, error = null) }
        val r = apiCall { block() }
        _state.update { it.copy(busy = null, task = (r as? ApiOutcome.Ok)?.value ?: it.task, error = r.errorText()) }
        if (r is ApiOutcome.Ok) after(r.value)
    }

    fun replan() = act("plan", { api.replan(taskId, key()) })
    fun accept(acknowledged: Boolean, onStarted: () -> Unit) = act("accept", { api.acceptPlan(taskId, key(), AcceptRequest(acknowledged)) }) { onStarted() }
    fun start(stepId: String) = act("start", { api.stepAction(taskId, stepId, "start", key()) })
    fun complete(stepId: String, onMoved: (TaskView) -> Unit) = act("move", { api.stepAction(taskId, stepId, "complete", key()) }, onMoved)
    fun skip(stepId: String, onMoved: (TaskView) -> Unit) = act("move", { api.stepAction(taskId, stepId, "skip", key()) }, onMoved)

    /** A full-resolution step photo: kept as the task's record, then the AI's suggested verdict is shown and spoken. */
    fun check(stepId: String, photo: File) = viewModelScope.launch {
        _state.update { it.copy(busy = "check", error = null) }
        val r = apiCall { api.checkStep(taskId, stepId, key(), MultipartBody.Part.createFormData("image", photo.name, photo.asRequestBody("image/jpeg".toMediaType()))) }
        _state.update { it.copy(busy = null, task = (r as? ApiOutcome.Ok)?.value?.task ?: it.task, error = r.errorText()) }
        (r as? ApiOutcome.Ok)?.value?.check?.let { c -> speaker.speak("${verdictText(c.verdict)}. ${c.reason}", force = true) }
    }

    /** A question by voice or text, with the latest camera frame when live coaching has one. The answer is spoken. */
    fun ask(question: String, frame: ByteArray?) = viewModelScope.launch {
        if (question.isBlank()) return@launch
        _state.update { it.copy(busy = "ask", error = null) }
        val r = apiCall { api.ask(taskId, key(), text(question.trim()), frame?.let { jpeg("image", it) }) }
        val answer = (r as? ApiOutcome.Ok)?.value?.answer
        _state.update { it.copy(busy = null, error = r.errorText(), answer = answer?.let { a -> question.trim() to a } ?: it.answer) }
        answer?.let { speaker.speak(it, force = true) }
        // The answer is now part of the task's record; reload it so conversation views show it.
        if (answer != null) refresh()
    }

    fun clearAnswer() = _state.update { it.copy(answer = null) }

    /** Ends free-form help. */
    fun finish(onDone: () -> Unit) = act("finish", { api.finishTask(taskId, key()) }) { onDone() }

    /** Asks about a full-resolution shutter photo, reduced to at most 1600 px on its long side before upload. */
    fun askAboutPhoto(question: String, photo: File) = viewModelScope.launch {
        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { downscaledJpeg(photo, 1600) }
        ask(question, bytes)
    }

    fun linkInspection(onLinked: (String) -> Unit) = viewModelScope.launch {
        _state.update { it.copy(busy = "link", error = null) }
        val r = apiCall { api.linkInspection(taskId, key()) }
        _state.update { it.copy(busy = null, task = (r as? ApiOutcome.Ok)?.value?.task ?: it.task, error = r.errorText()) }
        if (r is ApiOutcome.Ok) onLinked(r.value.inspectionId)
    }

    suspend fun analyzeLiveFrame(kind: String, bytes: ByteArray, frameId: String): LiveResponse =
        api.taskLiveFrame(taskId, kind, jpeg("image", bytes), text(frameId))
}

private fun downscaledJpeg(file: File, maxSide: Int): ByteArray? {
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
    val bitmap = if (degrees == 0f) decoded else android.graphics.Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, android.graphics.Matrix().apply { postRotate(degrees) }, true).also { if (it !== decoded) decoded.recycle() }
    return java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it); bitmap.recycle() }.toByteArray()
}
