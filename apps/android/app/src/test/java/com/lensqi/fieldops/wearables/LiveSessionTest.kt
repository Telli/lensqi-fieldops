package com.lensqi.fieldops.wearables

import com.lensqi.fieldops.data.api.FieldOpsApi
import com.lensqi.fieldops.di.NetworkModule
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** GT-R12.2: glasses frames reach the live endpoint of the session they serve, an inspection or a task. */
class LiveSessionTest {
    @Test
    fun `frames go to the inspection or the task live endpoint`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val base = server.url("/")
            val api = NetworkModule.retrofit(OkHttpClient.Builder().addInterceptor(NetworkModule.baseUrlInterceptor { base }).build(), NetworkModule.json())
                .create(FieldOpsApi::class.java)
            repeat(2) { server.enqueue(MockResponse().setBody("""{"frameId":"f","objects":[],"recommendation":"Hold steady"}""")) }

            LiveSession("I-1", LiveSessionKind.Inspection).analyze(api, "detect", byteArrayOf(1, 2, 3), "f")
            LiveSession("T-1", LiveSessionKind.Task, targetConcept = "drain plug").analyze(api, "guide", byteArrayOf(1, 2, 3), "f")

            assertEquals("/v1/inspections/I-1/live/detect", server.takeRequest().path)
            assertEquals("/v1/tasks/T-1/live/guide", server.takeRequest().path)
        } finally { server.shutdown() }
    }
}
