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
    override suspend fun nextPending(skip: List<String>) = rows.value.firstOrNull { it.state == CommandState.Pending && it.serverBase !in skip }
    private fun change(id: String, fn: (PendingCommand) -> PendingCommand) { rows.update { it.map { c -> if(c.id == id) fn(c) else c } } }
    override suspend fun countAttempt(id: String) = change(id) { it.copy(attempts = it.attempts + 1) }
    override suspend fun reject(id: String, code: String, message: String) = change(id) { it.copy(state = CommandState.Rejected, errorCode = code, errorMessage = message) }
    override suspend fun uploaded(id: String, evidenceId: String) = change(id) { it.copy(state = CommandState.Uploaded, resultEvidenceId = evidenceId) }
    override suspend fun retry(id: String, serverBase: String) = change(id) { it.copy(state = CommandState.Pending, serverBase = serverBase, errorCode = null, errorMessage = null) }
    override suspend fun delete(id: String) { rows.update { it.filterNot { c -> c.id == id } } }
}

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
