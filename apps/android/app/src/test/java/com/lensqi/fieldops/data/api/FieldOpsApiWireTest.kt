package com.lensqi.fieldops.data.api

import com.lensqi.fieldops.di.NetworkModule
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Each FieldOpsApi method sends the method and path the real server answered in the recordings, through the
 * base-URL interceptor, with an Idempotency-Key on every command.
 */
class FieldOpsApiWireTest {
    private lateinit var server: MockWebServer
    private lateinit var api: FieldOpsApi
    private val id get() = Recordings.call("inspection").path.substringAfterLast('/')

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        val base: HttpUrl = server.url("/")
        val client = OkHttpClient.Builder().addInterceptor(NetworkModule.baseUrlInterceptor { base }).build()
        api = NetworkModule.retrofit(client, NetworkModule.json()).create(FieldOpsApi::class.java)
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private suspend fun <T> exchange(recording: String, call: suspend FieldOpsApi.() -> T): Pair<T, RecordedRequest> {
        val rec = Recordings.call(recording)
        server.enqueue(MockResponse().setResponseCode(rec.status).setBody(Recordings.text(recording)))
        val value = api.call()
        val request = server.takeRequest()
        assertEquals(rec.method, request.method, "method for $recording")
        assertEquals(rec.path, request.requestUrl!!.encodedPath, "path for $recording")
        // Inspection commands are idempotent by key; the demo-only simulation control is a setting, not a command.
        if (rec.method == "POST" && rec.path.startsWith("/v1/inspections")) assertFalse(request.getHeader(FieldOpsApi.IDEMPOTENCY_KEY).isNullOrBlank(), "Idempotency-Key for $recording")
        return value to request
    }

    @Test
    fun reads_hit_the_recorded_paths() = runTest {
        exchange("health") { health() }
        exchange("fixtures") { fixtures() }
        exchange("inspection") { inspection(id) }
        exchange("knowledge") { knowledge(id) }
        exchange("receipt") { receipt(id) }
        exchange("receipt-verify") { verifyReceipt(id) }
        exchange("artifacts") { artifacts(id) }
    }

    @Test
    fun commands_hit_the_recorded_paths_with_an_idempotency_key() = runTest {
        exchange("inspection-created") { createInspection("k1", CreateInspectionRequest("LAB-RACK-01", "LAB-SW-01")) }
        exchange("investigate") { investigate(id, "k2") }
        val (_, seg) = exchange("segment") { segment(id, "k3", SegmentRequest(concept = "yellow ethernet cable")) }
        assertTrue(seg.body.readUtf8().contains("\"concept\":\"yellow ethernet cable\""))
        exchange("diagnostic-run-down") { runDiagnostic(id, "NODE-A-HEALTH", "k4") }
        exchange("work-order") { workOrder(id, "k5", WorkOrderRequest("Reseat the yellow cable at port 2")) }
        exchange("repair-claim-1") { repairClaim(id, "k6", RepairClaimRequest("Reseated the yellow cable")) }
        val first = Recordings.named("checklist-1-").first().file.removeSuffix(".json").substringAfter('-')
        val (_, check) = exchange(first) { checklist(id, "k7", ChecklistRequest("switch_chassis_visible", ChecklistStatus.Satisfied, "photo-x")) }
        assertTrue(check.body.readUtf8().contains("\"status\":\"Satisfied\""))
        exchange("acknowledge-1") { acknowledge(id, "k8") }
        val (decision, verify) = exchange("verify-denied") { verify(id, "k9") }
        assertEquals("k9", verify.getHeader(FieldOpsApi.IDEMPOTENCY_KEY))
        assertEquals(VerificationResult.Denied, decision.decision.result)
        exchange("simulation-set-down") { setSimulatedOutcome("NODE-A-HEALTH", SimulationRequest(false)) }
    }

    @Test
    fun live_frames_use_ephemeral_routes_and_keep_their_frame_identity() = runTest {
        server.enqueue(MockResponse().setBody("""{"frameId":"live-123","objects":[{"id":"part","concept":"cable","label":"cable","box":{"left":0.1,"top":0.2,"right":0.4,"bottom":0.8},"confidence":0.9}],"refreshAfterMs":4000,"inferenceMs":800}"""))
        val value = api.liveFrame(id, "detect", MultipartBody.Part.createFormData("image", "preview.jpg", byteArrayOf(1, 2).toRequestBody("image/jpeg".toMediaType())), "live-123".toRequestBody())
        val request = server.takeRequest()
        assertEquals("/v1/inspections/$id/live/detect", request.path)
        assertTrue(request.body.readUtf8().contains("live-123"))
        assertEquals("live-123", value.frameId)
        assertEquals(0.1, value.objects.single().box.left)
    }

    @Test
    fun photo_upload_is_multipart_with_the_original_bytes_source_and_device_time() = runTest {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
        val (_, req) = exchange("observation-initial") {
            uploadObservation(
                id, "k-photo",
                MultipartBody.Part.createFormData("image", "capture.jpg", jpeg.toRequestBody("image/jpeg".toMediaType())),
                "Phone".toRequestBody(), "2026-10-28T10:00:00Z".toRequestBody(),
            )
        }
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = req.body.readByteArray()
        val text = String(body, Charsets.ISO_8859_1)
        assertTrue(text.contains("name=\"image\"; filename=\"capture.jpg\""))
        assertTrue(text.contains(String(jpeg, Charsets.ISO_8859_1)))
        assertTrue(text.contains("name=\"source\"") && text.contains("Phone"))
        assertTrue(text.contains("name=\"observedAt\""))
    }

    @Test
    fun rejections_surface_the_server_code_verbatim() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody(Recordings.text("error-verify-closed")))
        val outcome = apiCall { api.verify(id, "k") }
        assertEquals(ApiOutcome.Rejected(409, "invalid_state", "Verification requires a pending repair claim (stage VerifiedComplete)"), outcome)

        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"unexpected\":true}"))
        assertEquals("client_contract_mismatch", (apiCall { api.health() } as ApiOutcome.Rejected).code)

        server.shutdown()
        assertTrue(apiCall { api.health() } is ApiOutcome.Unreachable)
    }
}
