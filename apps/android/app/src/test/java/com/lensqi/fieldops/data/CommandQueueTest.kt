package com.lensqi.fieldops.data

import com.lensqi.fieldops.data.api.*
import com.lensqi.fieldops.data.queue.*
import com.lensqi.fieldops.di.NetworkModule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MemoryCommands : PendingCommandDao {
    val rows = MutableStateFlow<List<PendingCommand>>(emptyList())
    override suspend fun insert(command: PendingCommand) { rows.update { it + command } }
    override fun observe(sessionId: String, serverBase: String) = rows.map { it.filter { c -> c.sessionId == sessionId && (c.serverBase == serverBase || c.serverBase == null) } }
    override suspend fun nextPending(skip: List<String>, skipSessions: List<String>) = rows.value.firstOrNull { it.state == CommandState.Pending && it.serverBase !in skip && it.sessionId !in skipSessions }
    override suspend fun rejectedSessions() = rows.value.filter { it.state == CommandState.Rejected }.map { it.sessionId }.distinct()
    private fun change(id: String, fn: (PendingCommand) -> PendingCommand) { rows.update { it.map { c -> if(c.id == id) fn(c) else c } } }
    override suspend fun countAttempt(id: String) = change(id) { it.copy(attempts = it.attempts + 1) }
    override suspend fun reject(id: String, code: String, message: String) = change(id) { it.copy(state = CommandState.Rejected, errorCode = code, errorMessage = message) }
    override suspend fun uploaded(id: String, evidenceId: String) = change(id) { it.copy(state = CommandState.Uploaded, resultEvidenceId = evidenceId) }
    override suspend fun retry(id: String, serverBase: String) = change(id) { it.copy(state = CommandState.Pending, serverBase = serverBase, errorCode = null, errorMessage = null, attempts = 0) }
    override suspend fun delete(id: String) { rows.update { it.filterNot { c -> c.id == id } } }
}

/** A step check sent with the recorded check answer, so the queue sees a 200 with the server's shape. */
private fun checkAnswer() = MockResponse().setBody(File("../../../docs/evidence/tasks/check-oil.json").readText())
private fun notNow() = MockResponse().setResponseCode(409).setBody("""{"code":"provider_unavailable","message":"Muse is down"}""")

class CommandQueueTest {
    private val json = NetworkModule.json()
    private fun api(base: String) = NetworkModule.retrofit(OkHttpClient(), json, base).create(FieldOpsApi::class.java)
    private val original = byteArrayOf(-1, -40, -1) + "original camera bytes".toByteArray()
    @Test fun `restart sends to capture destination with original bytes and keeps exact result until reviewed`(@TempDir dir: File) = runBlocking {
        MockWebServer().use { a -> MockWebServer().use { b ->
            a.start(); b.start()
            val dao = MemoryCommands()
            val file = File(dir, "shutter.jpg").apply { writeBytes(original) }
            val queue = CommandQueue(File(dir, "saved"), dao, ::api, InspectionCache()) {}
            val saved = queue.enqueuePhoto("I-1", file, "Phone", a.url("/").toString())
            assertTrue(queue.observe("I-1", b.url("/").toString()).first().isEmpty())
            a.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            assertEquals(DrainResult.Offline, queue.drain())
            val first = a.takeRequest()
            a.enqueue(MockResponse().setBody(Recordings.text("observation-initial").replace(Regex("\"sha256\"\\s*:\\s*\"[^\"]+\""), "\"sha256\":\"${CommandQueue.sha256(original)}\"")))
            // Simulate a restarted worker after Settings now points at B: it only knows the saved destination.
            val restarted = CommandQueue(File(dir, "saved"), dao, ::api, InspectionCache()) {}
            coroutineScope { awaitAll(async { restarted.drain() }, async { restarted.drain() }) }
            val sent = a.takeRequest()
            assertEquals(first.getHeader("Idempotency-Key"), sent.getHeader("Idempotency-Key"))
            assertTrue(sent.body.readByteString().indexOf(okio.ByteString.of(*original)) >= 0)
            assertEquals(0, b.requestCount)
            val result = dao.rows.value.single()
            assertEquals(CommandState.Uploaded, result.state)
            assertEquals(json.decodeFromString(ObservationResponse.serializer(), Recordings.text("observation-initial")).evidenceId, result.resultEvidenceId)
            assertTrue(File(saved.filePath!!).exists())
            restarted.dismiss(result)
            assertTrue(dao.rows.value.isEmpty()); assertFalse(File(saved.filePath).exists())
        } }
    }
    @Test fun `an unreachable server does not hold up photos for another server`(@TempDir dir: File) = runBlocking {
        MockWebServer().use { a -> MockWebServer().use { b ->
            a.start(); b.start()
            val dao = MemoryCommands()
            val queue = CommandQueue(File(dir, "saved"), dao, ::api, InspectionCache()) {}
            val shot = File(dir, "shot.jpg").apply { writeBytes(original) }
            val first = queue.enqueuePhoto("I-1", shot, "Phone", a.url("/").toString())
            val second = queue.enqueuePhoto("I-1", shot, "Phone", a.url("/").toString())
            val other = queue.enqueuePhoto("I-2", shot, "Phone", b.url("/").toString())
            a.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            b.enqueue(MockResponse().setBody(Recordings.text("observation-initial").replace(Regex("\"sha256\"\\s*:\\s*\"[^\"]+\""), "\"sha256\":\"${CommandQueue.sha256(original)}\"")))
            assertEquals(DrainResult.Offline, queue.drain())
            // A was asked once and its later photo kept its place behind the first; B's photo went through.
            assertEquals(1, a.requestCount)
            assertEquals(first.id, a.takeRequest().getHeader("Idempotency-Key"))
            assertEquals(listOf(CommandState.Pending, CommandState.Pending, CommandState.Uploaded), listOf(first, second, other).map { c -> dao.rows.value.single { it.id == c.id }.state })
        } }
    }
    @Test fun `a temporary shutter file is deleted once its saved copy exists`(@TempDir dir: File) = runBlocking {
        val scratch = File(dir, "cache").apply { mkdirs() }
        val dao = MemoryCommands()
        val queue = CommandQueue(File(dir, "saved"), dao, ::api, InspectionCache(), scratch) {}
        val shutter = File(scratch, "capture.jpg").apply { writeBytes(original) }
        val kept = File(dir, "picked.jpg").apply { writeBytes(original) }
        val saved = queue.enqueuePhoto("I-1", shutter, "Phone", "http://127.0.0.1:1/")
        queue.enqueuePhoto("I-1", kept, "Phone", "http://127.0.0.1:1/")
        assertFalse(shutter.exists())
        assertArrayEquals(original, File(saved.filePath!!).readBytes())
        assertTrue(kept.exists())
    }
    @Test fun `refused and legacy photos stay saved until an explicit choice`(@TempDir dir: File) = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"invalid_state","message":"step closed"}"""))
            val dao = MemoryCommands(); val q = CommandQueue(dir, dao, ::api, InspectionCache()) {}
            val bound = q.forServer(server.url("/").toString())
            val saved = bound.enqueueStepCheck("T-1", "s1", "same-photo-key", original)
            q.drain()
            assertEquals(CommandState.Rejected, dao.rows.value.single().state)
            assertArrayEquals(original, File(saved.filePath!!).readBytes())
            assertThrows(IllegalArgumentException::class.java) { runBlocking { q.retry(saved, "https://unrelated.test/") } }
            bound.retry(dao.rows.value.single())
            assertEquals(saved.id, dao.rows.value.single().id)
            bound.dismiss(saved)
            val legacy = saved.copy(id = "legacy", serverBase = null)
            dao.insert(legacy); q.drain()
            assertEquals("server_unbound", dao.rows.value.single().errorCode)
            assertEquals(1, server.requestCount)
        }
    }
}

/** The inspection's small JSON commands wait on the phone like photos: same key, capture order, the server's answer wins. */
class QueuedCommandTest {
    private val json = NetworkModule.json()
    private fun api(base: String) = NetworkModule.retrofit(OkHttpClient(), json, base).create(FieldOpsApi::class.java)
    private val original = byteArrayOf(-1, -40, -1) + "original camera bytes".toByteArray()

    @Test fun `a claim saved offline is sent after the photo before it, under its key, and the view it returns is cached`(@TempDir dir: File) = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val dao = MemoryCommands(); val cache = InspectionCache()
            val queue = CommandQueue(File(dir, "saved"), dao, ::api, cache) {}
            val base = server.url("/").toString()
            val photo = queue.enqueuePhoto("I-1", File(dir, "shot.jpg").apply { writeBytes(original) }, "Phone", base, measured = """[{"part":"switch","distanceCm":80}]""")
            val claim = queue.enqueueCommand("I-1", CommandKind.RepairClaim, "claim-key", """{"description":"Reseated the yellow cable"}""", base)
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            assertEquals(DrainResult.Offline, queue.drain())
            assertEquals(1, server.requestCount, "the claim waits behind the photo while the server is down")
            server.takeRequest()

            server.enqueue(MockResponse().setBody(Recordings.text("observation-initial").replace(Regex("\"sha256\"\\s*:\\s*\"[^\"]+\""), "\"sha256\":\"${CommandQueue.sha256(original)}\"")))
            server.enqueue(MockResponse().setBody(Recordings.text("repair-claim-1")))
            assertEquals(DrainResult.Empty, queue.drain())
            val upload = server.takeRequest()
            assertTrue(String(upload.body.readByteArray(), Charsets.ISO_8859_1).contains("name=\"measured\""), "what the phone measured rides with the photo")
            val sent = server.takeRequest()
            assertEquals("/v1/inspections/I-1/repair-claims", sent.path)
            assertEquals("claim-key", sent.getHeader("Idempotency-Key"))
            assertEquals("""{"description":"Reseated the yellow cable"}""", sent.body.readUtf8())
            assertNull(dao.rows.value.firstOrNull { it.id == claim.id }, "a sent command leaves the queue")
            assertEquals(CommandState.Uploaded, dao.rows.value.single { it.id == photo.id }.state)
            val claimed = json.decodeFromString(RepairClaimResponse.serializer(), Recordings.text("repair-claim-1")).state
            assertEquals(claimed.currentClaimId, cache.observe(claimed.inspectionId, base).first()?.currentClaimId)
        }
    }

    @Test fun `a refused command keeps the server's code and an unreadable body is refused on the phone`(@TempDir dir: File): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val dao = MemoryCommands()
            val queue = CommandQueue(File(dir, "saved"), dao, ::api, InspectionCache()) {}
            val base = server.url("/").toString()
            queue.enqueueCommand("I-1", CommandKind.Acknowledge, "ack-key", null, base)
            queue.enqueueCommand("I-1", CommandKind.Checklist, "bad-key", "not json", base)
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"invalid_state","message":"nothing to confirm"}"""))
            assertEquals(DrainResult.Empty, queue.drain())
            assertEquals("/v1/inspections/I-1/acknowledge", server.takeRequest().path)
            var rows = dao.rows.value.associateBy { it.id }
            assertEquals(CommandState.Rejected, rows["ack-key"]!!.state); assertEquals("invalid_state", rows["ack-key"]!!.errorCode)
            // The refusal holds the rest of its inspection: the next command waits until the technician removes the refused one.
            assertEquals(CommandState.Pending, rows["bad-key"]!!.state)
            queue.dismiss(rows["ack-key"]!!)
            assertEquals(DrainResult.Empty, queue.drain())
            rows = dao.rows.value.associateBy { it.id }
            assertEquals(CommandState.Rejected, rows["bad-key"]!!.state); assertEquals("payload_unreadable", rows["bad-key"]!!.errorCode)
            assertEquals(1, server.requestCount, "an unreadable body never reaches the server")
            assertThrows(IllegalArgumentException::class.java) { runBlocking { queue.enqueueCommand("I-1", CommandKind.UploadPhoto, "k", null, base) } }
        }
    }
}

/** What holds a row back: a server that answered "not now" holds its task, a server out of reach holds everything for it, a refusal holds what follows it. */
class QueueHoldTest {
    private val json = NetworkModule.json()
    private fun api(base: String) = NetworkModule.retrofit(OkHttpClient(), json, base).create(FieldOpsApi::class.java)
    private val original = byteArrayOf(-1, -40, -1) + "original camera bytes".toByteArray()
    private fun byPath(vararg answers: Pair<String, () -> MockResponse>) = object : okhttp3.mockwebserver.Dispatcher() {
        override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = answers.first { request.path.orEmpty().contains(it.first) }.second()
    }

    @Test fun `a model failure holds only its task, and another task's check on the same server still goes`(@TempDir dir: File) = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = byPath("/T-1/" to ::notNow, "/T-2/" to ::checkAnswer); server.start()
            val dao = MemoryCommands(); val queue = CommandQueue(File(dir, "saved"), dao, ::api, InspectionCache()) {}
            val bound = queue.forServer(server.url("/").toString())
            val first = bound.enqueueStepCheck("T-1", "s1", "t1-s1", original)
            val second = bound.enqueueStepCheck("T-1", "s2", "t1-s2", original)
            val other = bound.enqueueStepCheck("T-2", "s1", "t2-s1", original)
            assertEquals(DrainResult.Offline, queue.drain(), "the held row is tried again later")
            assertEquals(2, server.requestCount, "T-1's first check and T-2's check; T-1's second waits its turn")
            assertEquals(listOf(CommandState.Pending, CommandState.Pending), listOf(first, second).map { c -> dao.rows.value.single { it.id == c.id }.state })
            assertNull(dao.rows.value.firstOrNull { it.id == other.id }, "a sent check leaves the queue")
            assertEquals(1, dao.rows.value.single { it.id == first.id }.attempts)
        }
    }

    @Test fun `after five not-now answers the row is refused with that code, and a retry starts the count again`(@TempDir dir: File) = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = byPath("/" to ::notNow); server.start()
            val dao = MemoryCommands(); val queue = CommandQueue(File(dir, "saved"), dao, ::api, InspectionCache()) {}
            val bound = queue.forServer(server.url("/").toString())
            val saved = bound.enqueueStepCheck("T-1", "s1", "t1-s1", original)
            repeat(MAX_SERVER_ATTEMPTS - 1) { assertEquals(DrainResult.Offline, queue.drain()) }
            assertEquals(CommandState.Pending, dao.rows.value.single().state)
            assertEquals(DrainResult.Empty, queue.drain(), "nothing left for the worker: the row now waits for the person")
            val refused = dao.rows.value.single()
            assertEquals(CommandState.Rejected, refused.state); assertEquals("provider_unavailable", refused.errorCode); assertEquals(MAX_SERVER_ATTEMPTS, refused.attempts)
            assertEquals(MAX_SERVER_ATTEMPTS, server.requestCount)
            assertTrue(File(saved.filePath!!).exists(), "the photo stays for the person's retry or removal")
            bound.retry(refused)
            assertEquals(0, dao.rows.value.single().attempts); assertEquals(CommandState.Pending, dao.rows.value.single().state)
        }
    }

    @Test fun `runs while the server was unreachable do not count towards refusing a photo`(@TempDir dir: File) = runBlocking {
        val dead = MockWebServer().apply { start() }.also { it.shutdown() }
        MockWebServer().use { server ->
            server.dispatcher = byPath("/" to ::notNow); server.start()
            // While the server is down every request is refused at the socket; afterwards it answers "not now".
            var down = true
            val dao = MemoryCommands(); val queue = CommandQueue(File(dir, "saved"), dao, { base -> api(if (down) dead.url("/").toString() else base) }, InspectionCache()) {}
            queue.forServer(server.url("/").toString()).enqueueStepCheck("T-1", "s1", "t1-s1", original)
            repeat(MAX_SERVER_ATTEMPTS) { assertEquals(DrainResult.Offline, queue.drain()) }
            assertEquals(CommandState.Pending, dao.rows.value.single().state); assertEquals(0, dao.rows.value.single().attempts, "dropped connections are not answers")
            down = false
            assertEquals(DrainResult.Offline, queue.drain())
            val row = dao.rows.value.single()
            assertEquals(CommandState.Pending, row.state, "one not-now answer after a server outage is not five")
            assertEquals(1, row.attempts); assertEquals(1, server.requestCount)
        }
    }

    @Test fun `a server that cannot be reached holds every task for it, while another server's photo goes`(@TempDir dir: File) = runBlocking {
        val down = MockWebServer().apply { start() }.also { it.shutdown() }
        MockWebServer().use { up ->
            up.dispatcher = byPath("/" to ::checkAnswer); up.start()
            val dao = MemoryCommands(); val queue = CommandQueue(File(dir, "saved"), dao, ::api, InspectionCache()) {}
            val one = queue.forServer(down.url("/").toString()).enqueueStepCheck("T-1", "s1", "t1", original)
            val two = queue.forServer(down.url("/").toString()).enqueueStepCheck("T-2", "s1", "t2", original)
            val reachable = queue.forServer(up.url("/").toString()).enqueueStepCheck("T-3", "s1", "t3", original)
            assertEquals(DrainResult.Offline, queue.drain())
            assertEquals(listOf(CommandState.Pending, CommandState.Pending), listOf(one, two).map { c -> dao.rows.value.single { it.id == c.id }.state })
            assertNull(dao.rows.value.firstOrNull { it.id == reachable.id })
            assertEquals(1, up.requestCount)
        }
    }

    @Test fun `a refused step holds the task's later steps until the refused row is removed`(@TempDir dir: File) = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val dao = MemoryCommands(); val queue = CommandQueue(File(dir, "saved"), dao, ::api, InspectionCache()) {}
            val bound = queue.forServer(server.url("/").toString())
            bound.enqueueStepAction(PendingCommand("step-1", "T-1", CommandKind.TaskComplete, 1, stepId = "s1"))
            bound.enqueueStepAction(PendingCommand("step-2", "T-1", CommandKind.TaskComplete, 2, stepId = "s2"))
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"invalid_state","message":"The step is already done"}"""))
            server.enqueue(MockResponse().setBody(File("../../../docs/evidence/tasks/plan-oil.json").let { json.encodeToString(TaskView.serializer(), json.decodeFromString(CreateTaskResponse.serializer(), it.readText()).task) }))
            assertEquals(DrainResult.Empty, queue.drain())
            assertEquals(1, server.requestCount, "step 2 is never sent after step 1 was refused: the server moved on")
            assertEquals("/v1/tasks/T-1/steps/s1:complete", server.takeRequest().path)
            val rows = dao.rows.value.associateBy { it.id }
            assertEquals(CommandState.Rejected, rows["step-1"]!!.state); assertEquals(CommandState.Pending, rows["step-2"]!!.state)
            // A later run still holds it: the refusal is on the phone until the person removes it.
            assertEquals(DrainResult.Empty, queue.drain()); assertEquals(1, server.requestCount)
            bound.dismiss(rows["step-1"]!!)
            assertEquals(DrainResult.Empty, queue.drain())
            assertEquals("/v1/tasks/T-1/steps/s2:complete", server.takeRequest().path)
            assertTrue(dao.rows.value.isEmpty())
        }
    }

    @Test fun `a row this build cannot read is refused on the phone instead of crashing the worker`(@TempDir dir: File) = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val dao = MemoryCommands(); val queue = CommandQueue(File(dir, "saved"), dao, ::api, InspectionCache()) {}
            dao.insert(PendingCommand("no-step", "T-1", CommandKind.TaskComplete, 1, serverBase = server.url("/").toString()))
            assertEquals(DrainResult.Empty, queue.drain())
            assertEquals("row_unreadable", dao.rows.value.single().errorCode); assertEquals(CommandState.Rejected, dao.rows.value.single().state)
            assertEquals(0, server.requestCount)
        }
    }
}

/** A new answer never overtakes an older one still waiting in the queue. */
class QueuedBehindTest {
    private fun row(kind: CommandKind, state: CommandState) = PendingCommand("k-$kind-$state", "I-1", kind, 0, state = state)
    @Test fun `only pending JSON commands hold a new one behind them`() {
        assertFalse(emptyList<PendingCommand>().hasQueuedCommands())
        assertFalse(listOf(row(CommandKind.UploadPhoto, CommandState.Pending)).hasQueuedCommands(), "a photo upload has its own identity; an answer may go ahead of it")
        assertFalse(listOf(row(CommandKind.Checklist, CommandState.Rejected)).hasQueuedCommands(), "a refused answer is finished")
        assertTrue(listOf(row(CommandKind.Checklist, CommandState.Pending)).hasQueuedCommands())
        assertTrue(listOf(row(CommandKind.WorkOrder, CommandState.Pending), row(CommandKind.UploadPhoto, CommandState.Uploaded)).hasQueuedCommands())
    }
}

class OfflineStepSyncTest {
    @Test fun `a restarted worker replays the retained step key and saves the server answer for offline use`(@TempDir dir: File) = runBlocking {
        val json = NetworkModule.json()
        val task = json.decodeFromString(CreateTaskResponse.serializer(),
            File("../../../docs/evidence/tasks/plan-oil.json").readText()).task
        val dao = MemoryCommands()
        val snapshots = SnapshotStore(File(dir,"snapshots"),json)
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString()
            fun worker() = CommandQueue(File(dir,"captures"), dao,
                { NetworkModule.retrofit(OkHttpClient(),json,it).create(FieldOpsApi::class.java) }, InspectionCache(), snapshots = snapshots) {}
            val step = task.plan!!.steps.first().id
            val key = "offline-step-key"
            worker().forServer(base).enqueueStepAction(PendingCommand(key, task.taskId, CommandKind.TaskComplete, 1, stepId = step))
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(DrainResult.Offline, worker().drain())
            assertEquals(key, server.takeRequest().getHeader("Idempotency-Key"))
            server.enqueue(MockResponse().setBody(json.encodeToString(TaskView.serializer(),task)))
            assertEquals(DrainResult.Empty,worker().drain())
            val sent = server.takeRequest()
            assertEquals(key,sent.getHeader("Idempotency-Key"))
            assertEquals("/v1/tasks/${task.taskId}/steps/$step:complete",sent.path)
            assertTrue(dao.rows.value.isEmpty())
            assertEquals(task,TaskLibrary(snapshots,dir,base).job(task.taskId)?.value)
        }
    }
}
