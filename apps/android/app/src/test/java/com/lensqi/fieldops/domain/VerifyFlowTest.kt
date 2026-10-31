package com.lensqi.fieldops.domain

import com.lensqi.fieldops.data.api.ChecklistStatus
import com.lensqi.fieldops.data.api.DiagnosticResult
import com.lensqi.fieldops.data.api.DiagnosticSource
import com.lensqi.fieldops.data.api.EvidenceOrigin
import com.lensqi.fieldops.data.api.PhotoEvidence
import com.lensqi.fieldops.data.api.SegmentationRef
import com.lensqi.fieldops.data.api.VisualChangeAnalysis
import com.lensqi.fieldops.data.api.VisualChangeVerdict
import com.lensqi.fieldops.data.api.InspectionStage
import com.lensqi.fieldops.data.api.InspectionView
import com.lensqi.fieldops.data.api.Readiness
import com.lensqi.fieldops.data.api.Recordings
import com.lensqi.fieldops.data.api.VerificationResult
import com.lensqi.fieldops.di.NetworkModule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Kept in step with apps/web/src/lib/verify.test.ts: the same event shapes give the same reading on both clients. */
class VerifyFlowTest {
    private val json = NetworkModule.json()
    private var seq = 0L

    private fun e(kind: String, fields: Map<String, Any?> = emptyMap()): JsonObject = buildJsonObject {
        put("kind", kind); put("sequence", ++seq); put("occurredAt", "2026-10-30T10:00:%02dZ".format(seq))
        fields.forEach { (k, v) ->
            when (v) {
                is String -> put(k, v)
                is JsonObject -> put(k, v)
                null -> put(k, JsonPrimitive(null as String?))
            }
        }
    }

    private fun probe(evidenceId: String, passed: Boolean, status: Int? = null) = e("DiagnosticCompleted", mapOf("evidenceId" to evidenceId, "result" to buildJsonObject {
        put("fixtureId", "LAB-RACK-01"); put("diagnosticId", "NODE-A-HEALTH"); put("targetAlias", "lab-node-a"); put("passed", passed)
        put("completedAt", "2026-10-30T10:00:00Z"); put("source", "LiveLocal"); put("traceId", evidenceId); put("normalizedValue", if (passed) "Reachable" else "Unreachable")
        status?.let { put("httpStatus", it) }
    }))

    private fun decision(result: String, reason: String = "") = e("VerificationEvaluated", mapOf("decision" to buildJsonObject {
        put("result", result); putJsonArray("reasons") { add(JsonPrimitive(reason)) }; putJsonArray("verifiedClaims") {}
        put("policyVersion", "fieldproof-policy/1.4"); put("fixtureDigest", "x"); put("isVerified", result == "Verified")
    }))

    private fun attempt() = listOf(
        e("RepairClaimed", mapOf("claimId" to "c1")),
        e("PhotoRecorded", mapOf("evidenceId" to "p1")),
        e("ChecklistReviewed", mapOf("requirementId" to "switch_chassis_visible", "status" to "Unknown", "reviewedByEvidenceId" to "p1")),
        e("ChecklistReviewed", mapOf("requirementId" to "switch_chassis_visible", "status" to "Satisfied", "reviewedByEvidenceId" to "p1")),
        e("TechnicianAcknowledged", mapOf("claimId" to "c1")),
        probe("diag-manual", true),                       // run by hand just before verify
        probe("verify-diag-abc", false, status = 404),
        e("PredictionScored"),
        decision("Denied", "Diagnostic failed after the repair claim"),
        e("StageChanged", mapOf("to" to "VerificationFailed")),
    )

    @Test
    fun reads_the_decision_the_review_it_used_and_only_the_checks_verify_itself_ran() {
        val a = currentAttempt(attempt(), json)!!
        assertEquals(VerificationResult.Denied, a.decision.result)
        assertEquals(listOf(false), a.measured.map { it.passed })
        assertEquals("HTTP 404", measuredValue(a.measured.single()))
        assertEquals(listOf(AttemptReview("switch_chassis_visible", ChecklistStatus.Satisfied, "p1")), a.review)
        assertNotNull(a.acknowledgedAt)
    }

    @Test
    fun a_decision_made_before_any_check_ran_reports_no_measurement() {
        val a = currentAttempt(listOf(e("RepairClaimed"), e("PhotoRecorded"), decision("Denied", "Repair has not been acknowledged by the technician")), json)!!
        assertEquals(emptyList<DiagnosticResult>(), a.measured)
        assertNull(a.acknowledgedAt)
    }

    @Test
    fun new_evidence_review_confirmation_or_claim_retires_the_decision_from_the_screen() {
        assertNull(currentAttempt(emptyList(), json))
        for (kind in listOf("PhotoRecorded", "ChecklistReviewed", "TechnicianAcknowledged", "RepairClaimed"))
            assertNull(currentAttempt(attempt() + e(kind), json), kind)
    }

    @Test
    fun the_card_follows_the_servers_readiness_with_a_stage_fallback_for_an_older_server() {
        val base = NetworkModule.json().decodeFromString(InspectionView.serializer(), Recordings.text("inspection"))
            .copy(stage = InspectionStage.ReadyToVerify, currentClaimId = "c1", acceptedPostRepairPhotoId = "p1", technicianAcknowledged = false, awaitingAnalysis = emptyList(), readiness = null)
        fun ready(next: String) = base.copy(readiness = Readiness(ready = next == "Verify", next = next, blocking = emptyList()))
        assertEquals(VerifyCard.Comparing, verifyCard(ready("WaitForAnalysis")))
        assertEquals(VerifyCard.Run, verifyCard(ready("Verify")))
        assertEquals(VerifyCard.Closed, verifyCard(ready("None")))
        assertEquals(VerifyCard.Closed, verifyCard(ready("SomethingNewer")))
        assertEquals(VerifyCard.Review, verifyCard(base))
        assertEquals(VerifyCard.Claim, verifyCard(base.copy(stage = InspectionStage.VerificationFailed)))
        assertEquals(VerifyCard.Comparing, verifyCard(base.copy(awaitingAnalysis = listOf("p2"))))
    }

    @Test
    fun the_recorded_api_gives_the_card_and_the_attempt_the_screen_shows() {
        // fixtures/api-recordings: fake fix denied on a simulated failure, then the real fix verified.
        val denied = json.decodeFromString(com.lensqi.fieldops.data.api.VerifyResponse.serializer(), Recordings.text("verify-denied"))
        assertEquals("ClaimRepair", denied.state.readiness?.next)
        assertEquals(VerifyCard.Claim, verifyCard(denied.state))
        val verified = json.decodeFromString(InspectionView.serializer(), Recordings.text("inspection"))
        assertEquals(VerifyCard.Closed, verifyCard(verified))

        val events = Recordings.array("events").map { it as JsonObject }
        val a = currentAttempt(events, json)!!
        assertEquals(VerificationResult.Verified, a.decision.result)
        // Only the check verify ran for this attempt: not the manual one, not the earlier attempt's.
        assertEquals(1, a.measured.size)
        assertEquals(true, a.measured.single().passed)
        assertEquals(DiagnosticSource.Simulated, a.measured.single().source)
        assertNotNull(a.acknowledgedAt)
        assertEquals(setOf(ChecklistStatus.Satisfied), a.review.map { it.status }.toSet())
    }

    @Test
    fun a_measurement_reads_as_the_status_a_server_answered_else_the_closed_set_value() {
        val r = DiagnosticResult("F", "D", "t", false, "2026-10-30T10:00:00Z", DiagnosticSource.LiveLocal, "x", normalizedValue = "Unreachable")
        assertEquals("unreachable", measuredValue(r))
        assertEquals("HTTP 503", measuredValue(r.copy(httpStatus = 503)))
        assertEquals("—", measuredValue(r.copy(normalizedValue = null)))
    }

    @Test
    fun capture_help_comes_only_on_the_capture_step_and_follows_the_servers_reference() {
        val base = NetworkModule.json().decodeFromString(InspectionView.serializer(), Recordings.text("inspection")).copy(
            stage = InspectionStage.NeedsEvidence, currentClaimId = "c2", acceptedPostRepairPhotoId = null, awaitingAnalysis = emptyList(),
            photos = listOf("before", "older", "after1").map { PhotoEvidence(it, EvidenceOrigin.Phone, "sha-$it", "2026-10-31T10:00:00Z") },
            segmentations = listOf(
                SegmentationRef("s0", "older", "patch cable", matched = true),
                SegmentationRef("s1", "before", "yellow cable at port 2", matched = true),
                SegmentationRef("s2", "before", "chassis", matched = false),
                // The change check's own outline of an earlier claim's post-repair photo is never a reference.
                SegmentationRef("s3", "after1", "yellow cable at port 2", matched = true),
            ),
            changeAnalyses = listOf(VisualChangeAnalysis("c1", "after1", "before", "yellow cable at port 2", verdict = VisualChangeVerdict.NoVisualChange, reason = "", thresholdsVersion = "t1")),
            readiness = Readiness(ready = false, next = "CapturePhoto", blocking = emptyList()),
        )
        // Before this claim has a change analysis: the newest matched outline of a photo from before any claim.
        assertEquals(CaptureGuide("before", "yellow cable at port 2"), captureGuide(base))
        // Once the server names the reference for this claim, that is the one.
        val named = base.copy(changeAnalyses = base.changeAnalyses + VisualChangeAnalysis("c2", "after2", "older", "patch cable", verdict = VisualChangeVerdict.DuplicateImage, reason = "", thresholdsVersion = "t1"))
        assertEquals(CaptureGuide("older", "patch cable"), captureGuide(named))
        // Any other next step, or no claim yet, gets no capture help.
        for (next in listOf("ClaimRepair", "WaitForAnalysis", "ReviewChecklist", "Acknowledge", "Verify", "None"))
            assertNull(captureGuide(base.copy(readiness = Readiness(ready = false, next = next, blocking = emptyList()))), next)
        assertNull(captureGuide(base.copy(currentClaimId = null)))
        assertNull(captureGuide(base.copy(segmentations = base.segmentations.filter { it.sourcePhotoEvidenceId == "after1" })))
    }
}
