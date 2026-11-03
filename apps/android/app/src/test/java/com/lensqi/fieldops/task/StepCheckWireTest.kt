package com.lensqi.fieldops.task

import com.lensqi.fieldops.data.api.FieldOpsApi
import com.lensqi.fieldops.di.NetworkModule
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** A step check names its capture device, so a mock-glasses photo is never recorded as a phone photo. */
class StepCheckWireTest {
    private val check = File("../../../docs/evidence/tasks/check-oil.json").readText()

    @Test fun `the step check carries its source part when one is given and none otherwise`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val api = NetworkModule.retrofit(OkHttpClient.Builder().addInterceptor(NetworkModule.baseUrlInterceptor { server.url("/") }).build(), NetworkModule.json())
                .create(FieldOpsApi::class.java)
            repeat(2) { server.enqueue(MockResponse().setBody(check)) }
            val image = MultipartBody.Part.createFormData("image", "image.jpg", byteArrayOf(1, 2, 3).toRequestBody("image/jpeg".toMediaType()))

            api.checkStep("T-1", "s2", "k-1", image, "MockWearables".toRequestBody("text/plain".toMediaType()))
            val withSource = String(server.takeRequest().body.readByteArray(), Charsets.ISO_8859_1)
            assertTrue(withSource.contains("name=\"source\"") && withSource.contains("MockWearables"))

            val response = api.checkStep("T-1", "s2", "k-2", image)
            val request = server.takeRequest()
            assertEquals("/v1/tasks/T-1/steps/s2/check", request.path)
            assertFalse(String(request.body.readByteArray(), Charsets.ISO_8859_1).contains("name=\"source\""))
            assertEquals(response.check.stepId, response.task.plan!!.steps.first { it.id == response.check.stepId }.id)
        } finally { server.shutdown() }
    }
}
