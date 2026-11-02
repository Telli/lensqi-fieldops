package com.lensqi.fieldops.teaching

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Serializable enum class RecordingState { Preparing, Recording, Complete, Interrupted, Failed }
@Serializable enum class TeachingKind { RecordingStarted, ClockSample, Step, ActionStart, ActionEnd, Note, Outcome, Correction, Review, StopRequested, RecordingEnded }
@Serializable enum class TeachingSource { User, SpeechRecognition, CaptureSystem }

/** Device-monotonic times are retained independently of server/wall clocks. Video offsets are estimates from callbacks. */
@Serializable data class TeachingEvent(
    val sequence: Int, val kind: TeachingKind, val elapsedNs: Long?, val videoOffsetEstimateNs: Long?,
    val source: TeachingSource, val text: String = "", val stepId: String? = null, val refersTo: Int? = null,
    val recordedDurationNs: Long? = null, val recordedAt: String = java.time.Instant.now().toString(),
    val actionType: String? = null, val objectName: String? = null,
)

@Serializable data class TeachingConsent(
    val recordingAllowed: Boolean, val trainingAllowed: Boolean, val acceptedAt: String,
    val version: String = "teaching-consent-v1",
)

@Serializable data class VideoDetails(
    val width: Int, val height: Int, val rotationDegrees: Int, val durationUs: Long, val frameCount: Int,
    val firstPtsUs: Long, val lastPtsUs: Long, val medianFrameGapUs: Long, val maxFrameGapUs: Long,
    val largeGapCount: Int, val hasAudio: Boolean,
)

@Serializable data class TeachingRecord(
    val id: String, val goal: String, val equipment: String, val demonstrator: String,
    val consent: TeachingConsent, val createdAt: String, val requestedElapsedNs: Long,
    val device: String, val taskId: String? = null,
    val state: RecordingState = RecordingState.Preparing, val stopReason: String? = null,
    val video: VideoDetails? = null, val schemaVersion: Int = 1,
)

@Serializable data class TeachingExport(
    val schemaVersion: Int = 1, val format: String = "lensqi-teaching-observations",
    val record: TeachingRecord, val reviewedByRecorder: Boolean, val trainingEligible: Boolean,
    val files: Map<String, String>,
    val timing: String = "frames.csv contains MP4 presentation timestamps. Timeline elapsedNs uses Android elapsedRealtimeNanos. videoOffsetEstimateNs is estimated from CameraX callback receipt times; sensor-to-event alignment accuracy is unmeasured.",
    val labels: String = "Steps, actions, outcomes and corrections are human annotations. SpeechRecognition text requires review. context.json contains server records with their original provenance and timestamps; those records may predate this demonstration. User completion and AI verdicts are not independent verification.",
    val unavailable: List<String> = listOf("robot_actions", "joint_states", "hand_pose", "depth", "calibrated_camera_pose", "force_torque", "raw_audio"),
)

/** A local, single-writer store. All callers run on the recorder's serialized IO queue. */
class TeachingStore(private val root: File) {
    private val json = Json { encodeDefaults = true; prettyPrint = false }
    init { check(root.isDirectory || root.mkdirs()) { "Could not create the recording folder." } }

    fun folder(id: String): File {
        require(UUID.fromString(id).toString() == id) { "Invalid recording id." }
        return File(root, id)
    }
    fun video(id: String) = File(folder(id), "video.mp4")
    fun read(id: String): TeachingRecord = json.decodeFromString(File(folder(id), "session.json").readText())
    private fun timeline(id: String) = File(folder(id), "timeline.jsonl")
    /** An append cut off by a crash has no trailing newline and was never acknowledged, so it is not an event. */
    private fun completeLines(id: String): String = timeline(id).takeIf { it.exists() }?.readText()?.substringBeforeLast('\n', "").orEmpty()
    fun events(id: String): List<TeachingEvent> = completeLines(id).lines().filter { it.isNotBlank() }.map { json.decodeFromString<TeachingEvent>(it) }

    fun list(): List<TeachingRecord> = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull {
        runCatching { read(it.name) }.getOrNull()
    }.sortedByDescending { it.createdAt }

    fun create(goal: String, equipment: String, demonstrator: String, consent: TeachingConsent, now: String, elapsedNs: Long, device: String, taskId: String?): TeachingRecord {
        require(consent.recordingAllowed) { "Recording permission must be confirmed." }
        require(goal.trim().length in 3..200 && equipment.trim().length in 1..200 && demonstrator.trim().length in 1..80) { "Enter a goal, equipment and demonstrator alias within the field limits." }
        val record = TeachingRecord(UUID.randomUUID().toString(), goal.trim(), equipment.trim(), demonstrator.trim(), consent, now, elapsedNs, device, taskId)
        check(folder(record.id).mkdir()) { "Could not create the recording." }
        save(record)
        return record
    }

    fun save(record: TeachingRecord) = atomic(File(folder(record.id), "session.json"), json.encodeToString(TeachingRecord.serializer(), record))
    fun context(id: String, text: String) = atomic(File(folder(id), "context.json"), text)

    fun append(id: String, kind: TeachingKind, nowNs: Long, source: TeachingSource = TeachingSource.User, text: String = "", stepId: String? = null, refersTo: Int? = null, durationNs: Long? = null, actionType: String? = null, objectName: String? = null): TeachingEvent {
        val record = read(id)
        val previous = events(id)
        require(text.length <= 2000) { "Keep each annotation under 2,000 characters." }
        val postCapture = kind in setOf(TeachingKind.Outcome, TeachingKind.Correction, TeachingKind.Review)
        require(postCapture || nowNs >= (previous.lastOrNull { it.elapsedNs != null }?.elapsedNs ?: record.requestedElapsedNs)) { "Recording clock moved backwards." }
        val active = record.state == RecordingState.Recording
        if (kind in setOf(TeachingKind.ActionStart, TeachingKind.ActionEnd, TeachingKind.Step, TeachingKind.Note)) require(active) { "Start recording before adding annotations." }
        when (kind) {
            TeachingKind.ActionStart -> {
                require(openAction(previous) == null) { "End the current action before starting another." }
                require(actionType in actionTypes && !objectName.isNullOrBlank() && objectName.length <= 200 && text.isNotBlank()) { "Choose an action type and name the object or tool (up to 200 characters)." }
            }
            TeachingKind.ActionEnd -> require(refersTo != null && refersTo == openAction(previous)?.sequence) { "There is no matching open action." }
            TeachingKind.Step, TeachingKind.Note -> require(text.isNotBlank()) { "Enter the annotation text." }
            TeachingKind.Outcome -> require(record.video != null && text in outcomes) { "Review the video before setting an outcome." }
            TeachingKind.Correction -> {
                val original = previous.firstOrNull { it.sequence == refersTo && it.kind in editableKinds }
                require(record.video != null && text.isNotBlank() && original != null) { "Select an annotation to correct." }
                if (original.kind == TeachingKind.ActionStart) require(actionType in actionTypes && !objectName.isNullOrBlank() && objectName.length <= 200) { "Correct the action and object labels as well as the description." }
                if (original.kind == TeachingKind.Outcome) require(text in outcomes) { "An outcome can only be Succeeded, Failed or Uncertain." }
            }
            TeachingKind.Review -> require(record.video != null && previous.any { it.kind == TeachingKind.Outcome }) { "Set an outcome before marking this reviewed." }
            else -> Unit
        }
        // A capture callback's own recorded duration is exact; anything else extrapolates from the last pair.
        val clock = previous.lastOrNull { it.recordedDurationNs != null }
        val offset = if (postCapture) null else durationNs ?: clock?.let { it.recordedDurationNs!! + (nowNs - it.elapsedNs!!) }
        val event = TeachingEvent(previous.size + 1, kind, if (postCapture) null else nowNs, offset, source, text, stepId, refersTo, durationNs, actionType = actionType, objectName = objectName)
        trimTornTail(id)
        FileOutputStream(timeline(id), true).use { it.write((json.encodeToString(TeachingEvent.serializer(), event) + "\n").toByteArray()); it.fd.sync() }
        return event
    }

    private fun trimTornTail(id: String) {
        val file = timeline(id)
        val torn = file.length() > 0 && RandomAccessFile(file, "r").use { it.seek(it.length() - 1); it.read() != '\n'.code }
        if (torn) completeLines(id).let { atomic(file, if (it.isEmpty()) "" else it + "\n") }
    }

    /**
     * Records the end of a capture and labels it. Only a stop the user chose is Complete; a limit, a system stop or a
     * camera error keeps the video but is Interrupted, and an unreadable video is Failed.
     */
    fun finish(id: String, nowNs: Long, durationNs: Long, reason: String, interrupted: Boolean, video: VideoDetails?): TeachingRecord {
        openAction(events(id))?.let { append(id, TeachingKind.ActionEnd, nowNs, TeachingSource.CaptureSystem, text = "Recording stopped; action may be incomplete.", refersTo = it.sequence) }
        append(id, TeachingKind.RecordingEnded, nowNs, TeachingSource.CaptureSystem, reason, durationNs = durationNs)
        val state = when { video == null -> RecordingState.Failed; interrupted -> RecordingState.Interrupted; else -> RecordingState.Complete }
        return read(id).copy(state = state, stopReason = reason, video = video).also(::save)
    }

    fun reviewed(id: String): Boolean {
        val events = events(id)
        return events.lastOrNull { it.kind in editableKinds || it.kind == TeachingKind.Correction || it.kind == TeachingKind.Review }?.kind == TeachingKind.Review
    }

    /**
     * A crash never silently turns a partial recording into a completed demonstration. A video that CameraX finished
     * writing after the app stopped listening is still read, so it stays reviewable and exportable as Interrupted.
     */
    fun recover(inspect: (File) -> VideoDetails? = { null }) {
        val records = list()
        records.forEach { trimTornTail(it.id) }
        records.filter { it.state == RecordingState.Preparing || it.state == RecordingState.Recording }.forEach {
            val video = it.video ?: if (video(it.id).isFile) inspect(folder(it.id)) else null
            save(it.copy(state = RecordingState.Interrupted, stopReason = "App closed before recording finalized.", video = video))
        }
    }

    fun delete(id: String) { check(folder(id).deleteRecursively()) { "Could not delete the recording." } }

    fun export(id: String, output: OutputStream) {
        val record = read(id)
        require(record.video != null && video(id).isFile) { "This recording has no readable finalized video." }
        require(record.state !in setOf(RecordingState.Preparing, RecordingState.Recording)) { "Stop recording before exporting." }
        val required = listOf("session.json", "timeline.jsonl", "frames.csv", "video.mp4")
        require(required.all { File(folder(id), it).isFile }) { "The finalized recording is missing metadata." }
        val names = required + listOf("context.json").filter { File(folder(id), it).isFile }
        val hashes = names.associateWith { sha256(File(folder(id), it)) }
        val reviewed = reviewed(id)
        val manifest = TeachingExport(record = record, reviewedByRecorder = reviewed,
            trainingEligible = record.consent.trainingAllowed && reviewed && record.state == RecordingState.Complete,
            files = hashes)
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("dataset.json")); zip.write(json.encodeToString(TeachingExport.serializer(), manifest).toByteArray()); zip.closeEntry()
            for (name in names) { zip.putNextEntry(ZipEntry(name)); File(folder(id), name).inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
        }
    }

    companion object {
        val actionTypes = listOf("Reach", "Grasp", "Align", "Insert", "Release", "Adjust", "Inspect", "Retry", "Other")
        val outcomes = listOf("Succeeded", "Failed", "Uncertain")
        val editableKinds = setOf(TeachingKind.Step, TeachingKind.ActionStart, TeachingKind.ActionEnd, TeachingKind.Note, TeachingKind.Outcome)
        fun openAction(events: List<TeachingEvent>): TeachingEvent? = events.lastOrNull { it.kind == TeachingKind.ActionStart }
            ?.takeUnless { start -> events.any { it.kind == TeachingKind.ActionEnd && it.refersTo == start.sequence } }
        fun atomic(file: File, text: String) {
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.outputStream().use { it.write(text.toByteArray()); it.fd.sync() }
            check(temp.renameTo(file)) { "Could not save ${file.name}." }
        }
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
