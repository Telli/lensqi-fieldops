package com.lensqi.fieldops.task

import com.lensqi.fieldops.teaching.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipInputStream

class TeachingStoreTest {
    @TempDir lateinit var directory: Path
    private val now = "2026-11-01T12:00:00Z"
    private fun store() = TeachingStore(directory.toFile())
    private fun create(store: TeachingStore, training: Boolean = false) = store.create("Connect the test cable", "Lab switch", "tech-a",
        TeachingConsent(true, training, now), now, 1_000, "Unit test", null)
    private fun start(store: TeachingStore, record: TeachingRecord) {
        store.save(record.copy(state = RecordingState.Recording))
        store.append(record.id, TeachingKind.RecordingStarted, 2_000, TeachingSource.CaptureSystem, durationNs = 0)
    }
    private fun finish(store: TeachingStore, record: TeachingRecord, state: RecordingState = RecordingState.Complete) {
        store.video(record.id).writeBytes(byteArrayOf(1, 2, 3))
        java.io.File(store.folder(record.id), "frames.csv").writeText("frame_index,presentation_time_us,relative_time_us\n0,0,0\n1,33333,33333\n2,66666,66666\n")
        store.save(store.read(record.id).copy(state = state, video = VideoDetails(1280, 720, 0, 100_000, 3, 0, 66_666, 33_333, 33_333, 0, false)))
    }
    private fun archive(store: TeachingStore, id: String): Map<String, ByteArray> {
        val buffer = ByteArrayOutputStream(); store.export(id, buffer)
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(buffer.toByteArray().inputStream()).use { zip -> while (true) { val entry = zip.nextEntry ?: break; entries[entry.name] = zip.readBytes() } }
        return entries
    }
    private fun manifest(files: Map<String, ByteArray>) = Json.decodeFromString<TeachingExport>(files.getValue("dataset.json").decodeToString())

    @Test fun `recording is explicit and bounded`() {
        val store = store()
        assertThrows(IllegalArgumentException::class.java) { store.create("Connect cable", "Switch", "tech", TeachingConsent(false, true, now), now, 0, "test", null) }
        assertThrows(IllegalArgumentException::class.java) { store.create("x".repeat(201), "Switch", "tech", TeachingConsent(true, false, now), now, 0, "test", null) }
        assertTrue(store.list().isEmpty())
    }

    @Test fun `annotations retain a common clock and paired action boundaries`() {
        val store = store(); val record = create(store); start(store, record)
        val action = store.append(record.id, TeachingKind.ActionStart, 3_000, text = "Insert cable", actionType = "Insert", objectName = "cable")
        assertEquals(1_000L, action.videoOffsetEstimateNs)
        assertEquals("Insert", action.actionType)
        assertThrows(IllegalArgumentException::class.java) { store.append(record.id, TeachingKind.ActionStart, 4_000, text = "Grasp tool", actionType = "Grasp", objectName = "tool") }
        assertThrows(IllegalArgumentException::class.java) { store.append(record.id, TeachingKind.ActionEnd, 4_000, refersTo = 500) }
        store.append(record.id, TeachingKind.ActionEnd, 5_000, refersTo = action.sequence)
        assertNull(TeachingStore.openAction(store.events(record.id)))
        assertThrows(IllegalArgumentException::class.java) { store.append(record.id, TeachingKind.Note, 4_000, text = "Clock went back") }
        assertEquals(listOf(1, 2, 3), store.events(record.id).map { it.sequence })
    }

    @Test fun `recording state guards actions and post capture review works after device reboot`() {
        val store = store(); val record = create(store)
        assertThrows(IllegalArgumentException::class.java) { store.append(record.id, TeachingKind.Note, 2_000, text = "Not recording") }
        start(store, record); finish(store, record)
        // New boot's elapsed time is lower; post-capture labels have wall time, not a fabricated video time.
        val outcome = store.append(record.id, TeachingKind.Outcome, 10, text = "Failed")
        assertNull(outcome.elapsedNs); assertNull(outcome.videoOffsetEstimateNs)
        store.append(record.id, TeachingKind.Review, 11)
        assertTrue(store.reviewed(record.id))
        assertThrows(IllegalArgumentException::class.java) { store.append(record.id, TeachingKind.ActionStart, 6_000, text = "Insert cable", actionType = "Insert", objectName = "cable") }
    }

    @Test fun `training export requires both consent and review and retains failed demonstrations`() {
        val store = store(); val record = create(store, training = true); start(store, record); finish(store, record)
        assertFalse(manifest(archive(store, record.id)).trainingEligible)
        store.append(record.id, TeachingKind.Outcome, 4_000, text = "Failed")
        store.append(record.id, TeachingKind.Review, 5_000)
        val files = archive(store, record.id); val export = manifest(files)
        assertTrue(export.trainingEligible)
        assertTrue(files.getValue("timeline.jsonl").decodeToString().contains("Failed"))
        assertEquals(TeachingStore.sha256(store.video(record.id)), export.files["video.mp4"])
        val noConsent = create(store, training = false); start(store, noConsent); finish(store, noConsent)
        store.append(noConsent.id, TeachingKind.Outcome, 4_000, text = "Succeeded"); store.append(noConsent.id, TeachingKind.Review, 5_000)
        assertFalse(manifest(archive(store, noConsent.id)).trainingEligible)
    }

    @Test fun `correction is append only and invalidates review`() {
        val store = store(); val record = create(store, training = true); start(store, record)
        val note = store.append(record.id, TeachingKind.Note, 3_000, TeachingSource.SpeechRecognition, "Port one")
        finish(store, record)
        store.append(record.id, TeachingKind.Outcome, 4_000, text = "Succeeded"); store.append(record.id, TeachingKind.Review, 5_000)
        store.append(record.id, TeachingKind.Correction, 6_000, text = "Port two", refersTo = note.sequence)
        assertEquals("Port one", store.events(record.id).first { it.sequence == note.sequence }.text)
        assertFalse(store.reviewed(record.id)); assertFalse(manifest(archive(store, record.id)).trainingEligible)
        assertThrows(IllegalArgumentException::class.java) { store.append(record.id, TeachingKind.Correction, 7_000, text = "No target", refersTo = 999) }
    }

    @Test fun `action corrections carry replacement structured labels`() {
        val store = store(); val record = create(store); start(store, record)
        val action = store.append(record.id, TeachingKind.ActionStart, 3_000, text = "Insert cable", actionType = "Insert", objectName = "cable")
        store.append(record.id, TeachingKind.ActionEnd, 4_000, refersTo = action.sequence); finish(store, record)
        assertThrows(IllegalArgumentException::class.java) { store.append(record.id, TeachingKind.Correction, 5_000, text = "Inspect cable", refersTo = action.sequence) }
        val correction = store.append(record.id, TeachingKind.Correction, 6_000, text = "Inspect connector", refersTo = action.sequence, actionType = "Inspect", objectName = "connector")
        assertEquals("Inspect", correction.actionType); assertEquals("connector", correction.objectName)
        assertEquals("Insert", store.events(record.id).first { it.sequence == action.sequence }.actionType)
    }

    @Test fun `interrupted records are retained and cannot become eligible training data`() {
        val store = store(); val record = create(store, training = true); start(store, record)
        store.recover()
        assertEquals(RecordingState.Interrupted, store.read(record.id).state)
        assertThrows(IllegalArgumentException::class.java) { store.export(record.id, ByteArrayOutputStream()) }
        finish(store, record, RecordingState.Interrupted)
        store.append(record.id, TeachingKind.Outcome, 4_000, text = "Uncertain"); store.append(record.id, TeachingKind.Review, 5_000)
        assertFalse(manifest(archive(store, record.id)).trainingEligible)
    }

    private val details = VideoDetails(1280, 720, 0, 100_000, 3, 0, 66_666, 33_333, 33_333, 0, false)

    @Test fun `only a stop the user chose finishes as complete and an open action is closed`() {
        val store = store()
        val user = create(store); start(store, user)
        assertEquals(RecordingState.Complete, store.finish(user.id, 5_000, 3_000, "User stopped recording.", interrupted = false, video = details).state)
        val limit = create(store); start(store, limit)
        val action = store.append(limit.id, TeachingKind.ActionStart, 3_000, text = "Insert cable", actionType = "Insert", objectName = "cable")
        val ended = store.finish(limit.id, 6_000, 4_000, "Stopped at the 10-minute limit; the demonstration may be cut short.", interrupted = true, video = details)
        assertEquals(RecordingState.Interrupted, ended.state); assertEquals("Stopped at the 10-minute limit; the demonstration may be cut short.", ended.stopReason)
        assertEquals(action.sequence, store.events(limit.id).single { it.kind == TeachingKind.ActionEnd }.refersTo)
        assertNull(TeachingStore.openAction(store.events(limit.id)))
        val broken = create(store); start(store, broken)
        assertEquals(RecordingState.Failed, store.finish(broken.id, 5_000, 3_000, "User stopped recording.", interrupted = false, video = null).state)
    }

    @Test fun `capture events use their own recorded duration as the video offset`() {
        val store = store(); val record = create(store); start(store, record)
        assertEquals(4_000L, store.append(record.id, TeachingKind.ClockSample, 7_000, TeachingSource.CaptureSystem, durationNs = 4_000).videoOffsetEstimateNs)
        assertEquals(5_000L, store.append(record.id, TeachingKind.Note, 8_000, text = "After the sample").videoOffsetEstimateNs)
    }

    @Test fun `outcome corrections are limited to the outcome values`() {
        val store = store(); val record = create(store); start(store, record); finish(store, record)
        val outcome = store.append(record.id, TeachingKind.Outcome, 4_000, text = "Succeeded")
        assertThrows(IllegalArgumentException::class.java) { store.append(record.id, TeachingKind.Correction, 5_000, text = "mostly worked", refersTo = outcome.sequence) }
        assertEquals("Failed", store.append(record.id, TeachingKind.Correction, 5_000, text = "Failed", refersTo = outcome.sequence).text)
    }

    @Test fun `action errors name the actual problem`() {
        val store = store(); val record = create(store)
        val early = assertThrows(IllegalArgumentException::class.java) { store.append(record.id, TeachingKind.ActionStart, 3_000, text = "Insert cable", actionType = "Insert", objectName = "cable") }
        assertEquals("Start recording before adding annotations.", early.message)
        start(store, record)
        val label = assertThrows(IllegalArgumentException::class.java) { store.append(record.id, TeachingKind.ActionStart, 3_000, text = "Insert cable", actionType = "Insert", objectName = "c".repeat(201)) }
        assertEquals("Choose an action type and name the object or tool (up to 200 characters).", label.message)
    }

    @Test fun `recovery keeps a video finalized after the app stopped listening`() {
        val store = store(); val record = create(store, training = true); start(store, record)
        store.video(record.id).writeBytes(byteArrayOf(1, 2, 3))
        java.io.File(store.folder(record.id), "frames.csv").writeText("frame_index,presentation_time_us,relative_time_us\n0,0,0\n")
        store.recover { details }
        val recovered = store.read(record.id)
        assertEquals(RecordingState.Interrupted, recovered.state); assertEquals(details, recovered.video)
        store.append(record.id, TeachingKind.Outcome, 4_000, text = "Succeeded"); store.append(record.id, TeachingKind.Review, 5_000)
        assertFalse(manifest(archive(store, record.id)).trainingEligible)
    }

    @Test fun `a timeline line cut off by a crash is ignored and repaired`() {
        val store = store(); val record = create(store); start(store, record)
        val timeline = java.io.File(store.folder(record.id), "timeline.jsonl")
        timeline.appendText("{\"sequence\":2,\"kind\":\"No")
        assertEquals(listOf(1), store.events(record.id).map { it.sequence })
        store.append(record.id, TeachingKind.Note, 3_000, text = "After the crash")
        assertEquals(listOf(1, 2), store.events(record.id).map { it.sequence })
        assertTrue(timeline.readText().endsWith("\n"))
        timeline.appendText("{\"torn")
        store.recover()
        assertEquals(2, timeline.readLines().size)
    }

    @Test fun `export contains only allowlisted files and deletion is scoped to one recording`() {
        val store = store(); val record = create(store); start(store, record); finish(store, record)
        val other = create(store)
        java.io.File(store.folder(record.id), "unrelated-private.txt").writeText("do not export")
        assertFalse(archive(store, record.id).containsKey("unrelated-private.txt"))
        assertThrows(IllegalArgumentException::class.java) { store.folder("../../outside") }
        store.delete(record.id)
        assertEquals(other.id, store.list().single().id)
    }
}
