package com.lensqi.fieldops.data.api

import com.lensqi.fieldops.di.NetworkModule
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Every recorded response decodes into the model the client uses, and the fields the UI relies on hold real values. */
class RecordedContractTest {
    private val json = NetworkModule.json()

    private fun serializerFor(name: String): DeserializationStrategy<*> = when {
        name == "health" -> Health.serializer()
        name == "fixtures" -> ListSerializer(Fixture.serializer())
        name == "inspections" -> ListSerializer(InspectionSummary.serializer())
        name.startsWith("inspection-created") || name == "inspection" -> InspectionView.serializer()
        name.startsWith("simulation-") -> SimulationResponse.serializer()
        name.startsWith("observation-") -> ObservationResponse.serializer()
        name == "investigate" -> InvestigateResponse.serializer()
        name == "segment" -> SegmentResponse.serializer()
        name == "knowledge" -> KnowledgeResponse.serializer()
        name.startsWith("diagnostic-run") -> DiagnosticRunResponse.serializer()
        name.startsWith("repair-claim") -> RepairClaimResponse.serializer()
        name == "evidence-request" || name == "work-order" || name.startsWith("checklist-") || name.startsWith("acknowledge") -> InspectionView.serializer()
        name.startsWith("verify-") -> VerifyResponse.serializer()
        name == "events" -> ListSerializer(JsonObject.serializer())
        name == "artifacts" -> ListSerializer(String.serializer())
        name == "receipt" -> JsonObject.serializer()
        name == "receipt-verify" -> ReceiptVerification.serializer()
        name == "change-analysis" -> VisualChangeAnalysis.serializer()
        name.startsWith("error-") -> ApiError.serializer()
        else -> error("No model mapped for recording '$name'; add one when the recorder grows")
    }

    @TestFactory
    fun every_recording_decodes(): List<DynamicTest> = Recordings.calls.map { c ->
        val name = c.file.removeSuffix(".json").substringAfter('-')
        DynamicTest.dynamicTest("${c.file} (${c.method} ${c.status})") {
            assertNotNull(json.decodeFromString(serializerFor(name), Recordings.text(name)))
        }
    }

    @Test
    fun hero_flow_recordings_carry_server_decided_stages_and_labelled_sources() {
        val denied = json.decodeFromString(VerifyResponse.serializer(), Recordings.text("verify-denied"))
        assertEquals(VerificationResult.Denied, denied.decision.result)
        assertEquals(InspectionStage.VerificationFailed, denied.state.stage)
        assertTrue(denied.decision.reasons.isNotEmpty())

        val verified = json.decodeFromString(VerifyResponse.serializer(), Recordings.text("verify-verified"))
        assertEquals(VerificationResult.Verified, verified.decision.result)
        assertEquals(InspectionStage.VerifiedComplete, verified.state.stage)
        // The recordings use the simulated probe, so the outcome can only be the simulated kind.
        assertEquals(VerificationOutcomeKind.VerifiedCompleteSimulated, verified.decision.outcomeKind)
        assertTrue(verified.state.diagnostics.all { it.source == DiagnosticSource.Simulated })
    }

    @Test
    fun model_outputs_and_photo_signals_have_the_fields_the_screens_draw() {
        val investigate = json.decodeFromString(InvestigateResponse.serializer(), Recordings.text("investigate"))
        assertTrue(investigate.modelId.startsWith("muse-spark"))
        assertTrue(investigate.assessment.hypotheses.isNotEmpty())

        val seg = json.decodeFromString(SegmentResponse.serializer(), Recordings.text("segment")).segmentation
        assertEquals("LIVE_META", seg.label)
        if (seg.matched) {
            val m = seg.masks.first()
            assertEquals(listOf(m.height, m.width), m.rle.size)
            assertNotNull(seg.crop)
        } else {
            assertTrue(seg.masks.isEmpty())
            assertNull(seg.maskSha256)
        }

        val photo = json.decodeFromString(ObservationResponse.serializer(), Recordings.text("observation-initial"))
        assertEquals(EvidenceOrigin.Phone, photo.origin)
        assertTrue(photo.width >= 320 && photo.height >= 240)
        assertNotNull(photo.quality)
    }

    @Test
    fun a_reviewer_request_stays_open_until_the_next_photo_answers_it() {
        val asked = json.decodeFromString(InspectionView.serializer(), Recordings.text("evidence-request")).evidenceRequests.single()
        assertEquals("Operations desk", asked.requestedBy)
        assertNull(asked.answeredByPhotoId)
        val later = json.decodeFromString(InspectionView.serializer(), Recordings.text("inspection")).evidenceRequests.single { it.requestId == asked.requestId }
        assertNotNull(later.answeredByPhotoId)
        val fixture = json.decodeFromString(ListSerializer(Fixture.serializer()), Recordings.text("fixtures")).first()
        assertEquals("P2", fixture.connections.single().fromPortLabel)
    }

    @Test
    fun change_evidence_rejects_a_resubmitted_photo_and_accepts_a_changed_one() {
        val dup = json.decodeFromString(ObservationResponse.serializer(), Recordings.text("observation-duplicate"))
        assertEquals(VisualChangeVerdict.DuplicateImage, dup.changeAnalysis!!.verdict)
        assertEquals(InspectionStage.NeedsEvidence, dup.state.stage)
        assertNull(dup.state.acceptedPostRepairPhotoId)

        val moved = json.decodeFromString(ObservationResponse.serializer(), Recordings.text("observation-post-claim-1"))
        assertTrue(moved.changeAnalysis!!.verdict in setOf(VisualChangeVerdict.Changed, VisualChangeVerdict.Unscorable))
        assertEquals(moved.evidenceId, moved.state.acceptedPostRepairPhotoId)
        assertEquals("change_evidence_rejected", json.decodeFromString(ApiError.serializer(), Recordings.text("error-checklist-rejected")).code)
    }

    @Test
    fun error_bodies_carry_stable_codes() {
        assertEquals("invalid_state", json.decodeFromString(ApiError.serializer(), Recordings.text("error-verify-closed")).code)
        assertEquals("not_found", json.decodeFromString(ApiError.serializer(), Recordings.text("error-not-found")).code)
        assertEquals("image_too_small", json.decodeFromString(ApiError.serializer(), Recordings.text("error-image-too-small")).code)
        assertFalse(Recordings.calls.any { it.status >= 500 })
    }
}
