package com.lensqi.fieldops.task

import com.lensqi.fieldops.data.api.FieldOpsApi
import com.lensqi.fieldops.data.api.SkipRequest
import com.lensqi.fieldops.di.NetworkModule
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The new task fields through real Retrofit: height and tools on create, the measured parts beside a question and a live
 * frame, and a skip with its reason. A Retrofit declaration error only shows when a request is built, so each is sent.
 */
class TaskWireTest {
    private fun text(s: String) = s.toRequestBody("text/plain".toMediaType())

    @Test
    fun `height, tools, measured parts and a skip reason reach the server`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val base = server.url("/")
            val api = NetworkModule.retrofit(OkHttpClient.Builder().addInterceptor(NetworkModule.baseUrlInterceptor { base }).build(), NetworkModule.json())
                .create(FieldOpsApi::class.java)
            val task = File("../../../docs/evidence/tasks/plan-oil.json").readText()
            val taskOnly = NetworkModule.json().parseToJsonElement(task).let { (it as kotlinx.serialization.json.JsonObject)["task"].toString() }
            server.enqueue(MockResponse().setBody(task))
            server.enqueue(MockResponse().setBody("""{"answer":"Within reach.","modelId":"fake"}"""))
            server.enqueue(MockResponse().setBody("""{"frameId":"f","objects":[]}"""))
            server.enqueue(MockResponse().setBody(taskOnly))

            api.createTask("k1", text("Diy"), text("Test my ceiling smoke detector"), null, null, null, emptyList(), text("188"), listOf(text("step stool"), text("ladder")))
            api.ask("T-1", "k2", text("Do I need the stool?"), null, text("""[{"part":"smoke detector","distanceCm":95,"heightAboveFloorCm":240}]"""))
            api.taskLiveFrame("T-1", "guide", MultipartBody.Part.createFormData("image", "f.jpg", byteArrayOf(1).toRequestBody("image/jpeg".toMediaType())), text("f"),
                text("""[{"part":"smoke detector","distanceCm":95}]"""))
            api.skipStep("T-1", "s1", "k3", SkipRequest("Within reach", 240, 250))

            val create = server.takeRequest().body.readUtf8()
            assertTrue(create.contains("name=\"heightCm\"") && create.contains("188"))
            assertEquals(2, Regex("name=\"tools\"").findAll(create).count())
            assertTrue(server.takeRequest().body.readUtf8().contains("\"heightAboveFloorCm\":240"))
            assertTrue(server.takeRequest().body.readUtf8().contains("name=\"measured\""))
            val skip = server.takeRequest()
            assertEquals("/v1/tasks/T-1/steps/s1:skip", skip.path)
            assertEquals("""{"reason":"Within reach","partHeightCm":240,"reachCm":250}""", skip.body.readUtf8())
        } finally { server.shutdown() }
    }
}
