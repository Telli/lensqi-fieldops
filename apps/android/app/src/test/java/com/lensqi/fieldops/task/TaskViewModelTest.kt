package com.lensqi.fieldops.task

import androidx.lifecycle.SavedStateHandle
import com.lensqi.fieldops.data.api.CreateTaskResponse
import com.lensqi.fieldops.data.api.FieldOpsApi
import com.lensqi.fieldops.data.api.TaskPhase
import com.lensqi.fieldops.data.api.TaskView
import com.lensqi.fieldops.data.queue.CommandKind
import com.lensqi.fieldops.data.queue.CommandState
import com.lensqi.fieldops.data.queue.PendingCommand
import com.lensqi.fieldops.data.queue.StepCheckQueue
import com.lensqi.fieldops.di.NetworkModule
import com.lensqi.fieldops.ui.task.TaskUi
import com.lensqi.fieldops.ui.task.TaskViewModel
import com.lensqi.fieldops.voice.SpeechOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * GT-T15: the task view model against a scripted server. It keeps one key per pending action until the server answers,
 * shows refusals and reloads, saves an offline step check under its key, follows the queue, and speaks answers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskViewModelTest {
    @org.junit.jupiter.api.io.TempDir lateinit var temp: File
    private val json = NetworkModule.json()
    /** The recorded genuine Muse plan, started at its first step. */
    private val task: TaskView = json.decodeFromString(CreateTaskResponse.serializer(), File("../../../docs/evidence/tasks/plan-oil.json").readText()).task
        .let { it.copy(phase = TaskPhase.InProgress, currentStepId = it.plan!!.steps.first().id) }
    /** What the scripted server answers for the task; a test can change it to show a later state. */
    private var served: TaskView? = null
    private val taskJson get() = json.encodeToString(TaskView.serializer(), served ?: task)
    private val stepId get() = task.plan!!.steps.first().id

    private class FakeSpeech : SpeechOutput {
        val spoken: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override var enabled = true
        override var held = false
        override var listening = false
        override val busy get() = held || listening
        override fun speak(text: String, force: Boolean, replace: Boolean) { spoken += text }
        override fun stop() {}
    }

    private class FakeQueue : StepCheckQueue {
        val commands = MutableStateFlow<List<PendingCommand>>(emptyList())
        val queued: MutableList<Triple<String, String, String>> = Collections.synchronizedList(mutableListOf())
        val dismissed: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override suspend fun enqueueStepAction(command: PendingCommand) { commands.value += command }
        override fun observe(sessionId: String): Flow<List<PendingCommand>> = commands
        override suspend fun enqueueStepCheck(taskId: String, stepId: String, key: String, jpeg: ByteArray, source: String): PendingCommand =
            PendingCommand(key, taskId, CommandKind.StepCheckPhoto, 0, source = source, stepId = stepId).also { queued += Triple(taskId, stepId, key) }
        override suspend fun dismiss(command: PendingCommand) { dismissed += command.id }
    }

    private lateinit var server: MockWebServer
    private val requests = ConcurrentLinkedQueue<RecordedRequest>()
    /** Scripted answers for commands, by path suffix; anything unscripted answers with the task. */
    private val scripted = ConcurrentLinkedQueue<Pair<String, MockResponse>>()
    private val speech = FakeSpeech()
    private val queue = FakeQueue()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val path = request.path.orEmpty()
                    scripted.firstOrNull { path.endsWith(it.first) && request.method == "POST" }?.let { scripted.remove(it); return it.second }
                    return MockResponse().setBody(taskJson)
                }
            }
            start()
        }
    }

    @AfterEach
    fun tearDown() { server.shutdown(); Dispatchers.resetMain() }

    private fun api(base: okhttp3.HttpUrl = server.url("/")): FieldOpsApi =
        NetworkModule.retrofit(OkHttpClient.Builder().addInterceptor(NetworkModule.baseUrlInterceptor { base }).build(), json).create(FieldOpsApi::class.java)

    private fun viewModel(api: FieldOpsApi = api(), spoken: com.lensqi.fieldops.ui.task.SpokenChecks = com.lensqi.fieldops.ui.task.SpokenChecks()) =
        TaskViewModel(SavedStateHandle(mapOf("taskId" to task.taskId)), api, speech, queue, spoken, com.lensqi.fieldops.data.TaskLibrary(com.lensqi.fieldops.data.SnapshotStore(temp, json), temp, "test"))

    @Test
    fun `a check that synced while two screens hold the task is spoken once`() {
        val shared = com.lensqi.fieldops.ui.task.SpokenChecks()
        val plan = viewModel(spoken = shared); val step = viewModel(spoken = shared)
        await(plan) { it.task != null }; await(step) { it.task != null }
        queue.commands.value = listOf(PendingCommand("k-1", task.taskId, CommandKind.StepCheckPhoto, 0, stepId = stepId))
        until { plan.state.value.queuedChecks == 1 && step.state.value.queuedChecks == 1 }
        served = task.copy(plan = task.plan!!.copy(steps = task.plan!!.steps.mapIndexed { i, s ->
            if (i == 0) s.copy(lastCheck = com.lensqi.fieldops.data.api.TaskStepCheck(stepId, "step-1", com.lensqi.fieldops.data.api.StepVerdict.LooksDone, "The plug is back in.")) else s }))
        queue.commands.value = emptyList()
        until { plan.state.value.task?.currentStep?.lastCheck != null && step.state.value.task?.currentStep?.lastCheck != null }
        assertEquals(1, speech.spoken.count { it.startsWith("Looks done") }, "one verdict, one announcement, whichever screen saw it first")
    }

    private fun await(vm: TaskViewModel, what: (TaskUi) -> Boolean): TaskUi = runBlocking { withTimeout(5000) { vm.state.first(what) } }
    private fun until(what: () -> Boolean) = runBlocking { withTimeout(5000) { while (!what()) kotlinx.coroutines.delay(10) } }
    private fun keysFor(suffix: String) = requests.filter { it.method == "POST" && it.path.orEmpty().endsWith(suffix) }.map { it.getHeader("Idempotency-Key") }
    private fun gets() = requests.count { it.method == "GET" && it.path == "/v1/tasks/${task.taskId}" }
    private fun error(status: Int, code: String, message: String) =
        MockResponse().setResponseCode(status).setBody("""{"code":"$code","message":"$message"}""")

    @Test
    fun `a transient step failure is queued exactly once under its original key`() {
        val vm = viewModel()
        assertEquals(stepId, await(vm) { it.task != null }.task!!.currentStepId)

        scripted += ":complete" to error(503, "http_503", "busy")
        vm.complete(stepId) {}
        until { keysFor(":complete").size == 1 && vm.state.value.busy == null }
        val saved = queue.commands.value.single()
        assertEquals(keysFor(":complete").single(), saved.id)
        vm.complete(stepId) {}
        until { vm.state.value.busy == null }
        assertEquals(1, queue.commands.value.size)
        assertEquals(1, keysFor(":complete").size)

    }

    @Test
    fun `a cold offline job reopens its accepted plan and pending step without claiming completion`() {
        await(viewModel()) { it.task != null }
        val down = MockWebServer().apply { start() }.also { it.shutdown() }
        val offline = viewModel(api(down.url("/")))
        assertEquals(stepId, await(offline) { it.cachedAt != null }.task!!.currentStepId)
        offline.complete(stepId) {}
        until { queue.commands.value.any { it.kind == CommandKind.TaskComplete } }
        val reopened = viewModel(api(down.url("/")))
        val ui = await(reopened) { it.cachedAt != null && it.pendingSteps.isNotEmpty() }
        assertEquals(stepId, ui.task!!.currentStepId)
        assertNotEquals(stepId, ui.displayed!!.currentStepId)
        assertEquals(TaskPhase.InProgress, ui.displayed!!.phase)
    }

    @Test
    fun `an older snapshot never replaces a newer one and server offsets are read`() = runBlocking {
        val library = com.lensqi.fieldops.data.TaskLibrary(com.lensqi.fieldops.data.SnapshotStore(temp, json), temp, "server-a")
        val newer = task.copy(updatedAt = "2026-10-29T01:00:00.000000+00:00", goal = "newer")
        val older = task.copy(updatedAt = "2026-10-29T00:59:00Z", goal = "older")
        library.remember(newer); library.remember(older)
        assertEquals("newer", library.job(task.taskId)?.value?.goal)
        library.remember(older.copy(updatedAt = "2026-10-29T01:01:00+00:00", goal = "newest"))
        assertEquals("newest", library.job(task.taskId)?.value?.goal)
        library.remember(older.copy(updatedAt = "not a time", goal = "unreadable"))
        assertEquals("unreadable", library.job(task.taskId)?.value?.goal)
    }

    @Test
    fun `cached reference bytes belong to the job and the server that supplied them`() = runBlocking {
        val snapshots = com.lensqi.fieldops.data.SnapshotStore(temp, json)
        val first = com.lensqi.fieldops.data.TaskLibrary(snapshots, temp, "server-a")
        val other = com.lensqi.fieldops.data.TaskLibrary(snapshots, temp, "server-b")
        val bytes = "stored page".toByteArray()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("stored page")
        }
        val page = com.lensqi.fieldops.data.api.TaskPhoto("p1", "Manual", sha256 = com.lensqi.fieldops.data.queue.CommandQueue.sha256(bytes))
        val job = task.copy(photos = listOf(page, page.copy(evidenceId = "corrupt", sha256 = "wrong")))
        first.remember(job); first.cacheReferences(job, api())
        assertTrue(first.reference(task.taskId, "p1").readBytes().contentEquals(bytes))
        assertFalse(first.reference(task.taskId, "corrupt").exists())
        assertFalse(other.reference(task.taskId, "p1").exists())
        assertNull(other.job(task.taskId))
        assertEquals(task.taskId, first.history().single().taskId)
    }

    @Test
    fun `a refusal is shown, drops the key and reloads the task`() {
        val vm = viewModel()
        await(vm) { it.task != null }
        val loads = gets()
        scripted += ":skip" to error(409, "invalid_state", "The step is already done")
        vm.skip(stepId) {}
        assertTrue(await(vm) { it.error != null }.error!!.isNotBlank())
        until { gets() > loads }
        vm.skip(stepId) {}
        until { keysFor(":skip").size == 2 }
        assertNotEquals(keysFor(":skip")[0], keysFor(":skip")[1])
    }

    @Test
    fun `without a connection a step check is saved on the phone under its key`() {
        val down = MockWebServer().apply { start() }.also { it.shutdown() }   // a port nothing answers on
        val vm = viewModel(api(down.url("/")))
        vm.reduce = { _, _ -> byteArrayOf(1, 2, 3) }
        vm.check(stepId, File.createTempFile("offline-photo", ".jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, 3)) })
        val ui = await(vm) { it.error?.contains("saved on the phone") == true }
        assertNull(ui.busy)
        until { queue.queued.isNotEmpty() }
        val (taskId, step, key) = queue.queued.single()
        assertEquals(task.taskId to stepId, taskId to step)
        assertTrue(key.isNotBlank())
    }

    @Test
    fun `queued checks are counted, a finished one reloads the task, a refused one is retained until explicit removal`() {
        val vm = viewModel()
        await(vm) { it.task != null }
        fun check(id: String, state: CommandState = CommandState.Pending, message: String? = null) =
            PendingCommand(id, task.taskId, CommandKind.StepCheckPhoto, 0, stepId = stepId, state = state, errorCode = message?.let { "invalid_state" }, errorMessage = message)

        queue.commands.value = listOf(check("a"), check("b"))
        assertEquals(2, await(vm) { it.queuedChecks == 2 }.queuedChecks)
        val loads = gets()
        queue.commands.value = listOf(check("b"))
        until { gets() > loads }
        queue.commands.value = listOf(check("b", CommandState.Rejected, "The step is already done"))
        assertTrue(await(vm) { it.error?.contains("Refresh and review where things stand") == true }.queuedChecks == 0)
        assertTrue(queue.dismissed.isEmpty())
        vm.removeCheck(vm.state.value.refusedChecks.single())
        until { queue.dismissed == listOf("b") }
    }

    @Test
    fun `an answer is shown and spoken, and stopping the task reports back`() {
        val vm = viewModel()
        await(vm) { it.task != null }
        scripted += "/ask" to MockResponse().setBody("""{"answer":"The drain plug is the bolt at the lowest point of the pan.","modelId":"fake"}""")
        vm.ask("Where is the drain plug?", null)
        val ui = await(vm) { it.answer != null }
        assertEquals("Where is the drain plug?", ui.answer!!.first)
        until { speech.spoken.contains("The drain plug is the bolt at the lowest point of the pan.") }

        var stopped = false
        vm.abandon { stopped = true }
        until { stopped }
        assertTrue(requests.any { it.path == "/v1/tasks/${task.taskId}/abandon" && it.body.readUtf8().contains("Stopped by the user") })
    }

    @Test
    fun `a retaken photo has a new request identity and keeps its original bytes`() {
        val vm = viewModel()
        await(vm) { it.task != null }
        vm.reduce = { _, _ -> "reduced derivative".toByteArray() }
        val a = File.createTempFile("capture-a", ".jpg").apply { writeText("original capture A") }
        val b = File.createTempFile("capture-b", ".jpg").apply { writeText("original capture B") }
        try {
            scripted += "/check" to error(409, "provider_unavailable", "try again")
            vm.check(stepId, a)
            until { keysFor("/check").size == 1 && vm.state.value.busy == null }
            scripted += "/check" to error(409, "provider_unavailable", "try again")
            vm.check(stepId, b)
            until { keysFor("/check").size == 2 && vm.state.value.busy == null }
            assertNotEquals(keysFor("/check")[0], keysFor("/check")[1])
            val bodies = requests.filter { it.path.orEmpty().endsWith("/check") }.map { it.body.clone().readUtf8() }
            assertTrue(bodies[0].contains("original capture A"))
            assertTrue(bodies[1].contains("original capture B"))
        } finally { a.delete(); b.delete() }
    }

    @Test
    fun `a photo over the server upload limit is sent as a reduced copy instead of being refused`() {
        val vm = viewModel()
        await(vm) { it.task != null }
        vm.reduce = { _, side -> "reduced to $side".toByteArray() }
        val large = File.createTempFile("capture-large", ".jpg").apply { writeBytes(ByteArray((com.lensqi.fieldops.ui.task.MAX_UPLOAD_BYTES + 1).toInt())) }
        try {
            scripted += "/check" to error(409, "provider_unavailable", "try again")
            vm.check(stepId, large)
            until { keysFor("/check").size == 1 && vm.state.value.busy == null }
            val body = requests.single { it.path.orEmpty().endsWith("/check") }
            assertTrue(body.bodySize < 1024 * 1024)
            assertTrue(body.body.clone().readUtf8().contains("reduced to 2400"))
        } finally { large.delete() }
    }
}
