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
    private val json = NetworkModule.json()
    /** The recorded genuine Muse plan, started at its first step. */
    private val task: TaskView = json.decodeFromString(CreateTaskResponse.serializer(), File("../../../docs/evidence/tasks/plan-oil.json").readText()).task
        .let { it.copy(phase = TaskPhase.InProgress, currentStepId = it.plan!!.steps.first().id) }
    private val taskJson get() = json.encodeToString(TaskView.serializer(), task)
    private val stepId get() = task.plan!!.steps.first().id

    private class FakeSpeech : SpeechOutput {
        val spoken: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override var enabled = true
        override var held = false
        override fun speak(text: String, force: Boolean) { spoken += text }
        override fun stop() {}
    }

    private class FakeQueue : StepCheckQueue {
        val commands = MutableStateFlow<List<PendingCommand>>(emptyList())
        val queued: MutableList<Triple<String, String, String>> = Collections.synchronizedList(mutableListOf())
        val dismissed: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun observe(sessionId: String): Flow<List<PendingCommand>> = commands
        override suspend fun enqueueStepCheck(taskId: String, stepId: String, key: String, jpeg: ByteArray): PendingCommand =
            PendingCommand(key, taskId, CommandKind.StepCheckPhoto, 0, stepId = stepId).also { queued += Triple(taskId, stepId, key) }
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

    private fun viewModel(api: FieldOpsApi = api()) = TaskViewModel(SavedStateHandle(mapOf("taskId" to task.taskId)), api, speech, queue)

    private fun await(vm: TaskViewModel, what: (TaskUi) -> Boolean): TaskUi = runBlocking { withTimeout(5000) { vm.state.first(what) } }
    private fun until(what: () -> Boolean) = runBlocking { withTimeout(5000) { while (!what()) kotlinx.coroutines.delay(10) } }
    private fun keysFor(suffix: String) = requests.filter { it.method == "POST" && it.path.orEmpty().endsWith(suffix) }.map { it.getHeader("Idempotency-Key") }
    private fun gets() = requests.count { it.method == "GET" && it.path == "/v1/tasks/${task.taskId}" }
    private fun error(status: Int, code: String, message: String) =
        MockResponse().setResponseCode(status).setBody("""{"code":"$code","message":"$message"}""")

    @Test
    fun `loads the task and keeps one key per action until the server answers`() {
        val vm = viewModel()
        assertEquals(stepId, await(vm) { it.task != null }.task!!.currentStepId)

        scripted += ":complete" to error(503, "http_503", "busy")
        vm.complete(stepId) {}
        until { keysFor(":complete").size == 1 && vm.state.value.busy == null }
        vm.complete(stepId) {}
        until { keysFor(":complete").size == 2 && vm.state.value.busy == null }
        vm.complete(stepId) {}
        until { keysFor(":complete").size == 3 }

        val keys = keysFor(":complete")
        assertEquals(keys[0], keys[1], "a server error keeps the key, so the server can replay")
        assertNotEquals(keys[1], keys[2], "an answered action starts a new one")
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
        vm.check(stepId, File("unused.jpg"))
        val ui = await(vm) { it.error?.contains("saved on the phone") == true }
        assertNull(ui.busy)
        until { queue.queued.isNotEmpty() }
        val (taskId, step, key) = queue.queued.single()
        assertEquals(task.taskId to stepId, taskId to step)
        assertTrue(key.isNotBlank())
    }

    @Test
    fun `queued checks are counted, a finished one reloads the task, a refused one is reported and removed`() {
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
        assertTrue(await(vm) { it.error?.contains("The step is already done") == true }.queuedChecks == 0)
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
}
