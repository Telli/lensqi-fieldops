package com.lensqi.fieldops.data

import com.lensqi.fieldops.data.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * A server-bound copy of confirmed jobs. Pending actions stay separate in the Room outbox. The server base is read per
 * call, so a screen that stays open across a server switch files its history under the server it now talks to.
 */
class TaskLibrary(private val snapshots: SnapshotStore, private val directory: File, private val base: () -> String) {
    constructor(snapshots: SnapshotStore, directory: File, server: String) : this(snapshots, directory, { server })
    val server: String get() = base()
    private fun key(kind: String, id: String = "") = SnapshotStore.key(server, kind, id)
    private val summaries = ListSerializer(TaskSummary.serializer())

    suspend fun history() = withContext(Dispatchers.IO) { snapshots.load(key("tasks"), summaries)?.value.orEmpty() }
    suspend fun history(list: List<TaskSummary>) = withContext(Dispatchers.IO) { snapshots.save(key("tasks"), summaries, list) }
    suspend fun job(id: String) = withContext(Dispatchers.IO) { snapshots.load(key("task", id), TaskView.serializer()) }
    suspend fun remember(t: TaskView) = withContext(Dispatchers.IO) {
        synchronized(snapshots) {
            val previous = snapshots.load(key("task", t.taskId), TaskView.serializer())
            if (previous != null && at(previous.value.updatedAt)?.let { kept -> at(t.updatedAt)?.let { kept > it } } == true) return@synchronized
            snapshots.save(key("task", t.taskId), TaskView.serializer(), t)
            val steps = t.plan?.steps.orEmpty()
            val summary = TaskSummary(t.taskId, t.mode, t.goal, t.title ?: t.plan?.title, t.phase, steps.size,
                steps.count { it.status == StepStatus.Done || it.status == StepStatus.Skipped }, t.currentStep?.title, t.inspectionId, t.updatedAt, titleByAi = t.titleByAi)
            val list = snapshots.load(key("tasks"), summaries)?.value.orEmpty()
            snapshots.save(key("tasks"), summaries, listOf(summary) + list.filter { it.taskId != t.taskId })
        }
    }

    /** Server timestamps carry an offset ("+00:00"), which `Instant.parse` rejects on API 31 and 32; an unreadable one never blocks a save. */
    private fun at(s: String) = runCatching { java.time.OffsetDateTime.parse(s).toInstant() }.getOrNull()

    fun reference(taskId: String, photoId: String) = File(directory, key("task-reference:$taskId", photoId))
    suspend fun cacheReferences(t: TaskView, api: FieldOpsApi) = withContext(Dispatchers.IO) {
        for (page in t.photos.filter { it.purpose == "Manual" }) {
            val file = reference(t.taskId, page.evidenceId)
            if (file.exists()) continue
            val result = apiCall { api.taskReference(t.taskId, page.evidenceId).use { it.bytes() } }
            if (result is ApiOutcome.Ok && (page.sha256 == null || page.sha256 == com.lensqi.fieldops.data.queue.CommandQueue.sha256(result.value))) {
                directory.mkdirs()
                val temp = File.createTempFile("reference", ".part", directory)
                try { temp.writeBytes(result.value); temp.renameTo(file) } finally { temp.delete() }
            }
        }
    }
}
