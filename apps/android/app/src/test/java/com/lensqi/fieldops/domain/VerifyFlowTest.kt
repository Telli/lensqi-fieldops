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
import com.lensqi.fieldops.data.api.ReadinessCheck
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

/** The attempt history and the preflight read the same server view and event log the cards use; nothing is decided here. */
class VerifyHistoryAndPreflightTest {
    private val json = NetworkModule.json()
    private var seq = 0L
    private fun e(kind: String, fields: Map<String, String> = emptyMap()): JsonObject = buildJsonObject {
        put("kind", kind); put("sequence", ++seq); put("occurredAt", "2026-10-30T10:00:%02dZ".format(seq)); fields.forEach { (k, v) -> put(k, v) }
    }
    private fun decision(result: String, reason: String) = e("VerificationEvaluated") .let { base -> buildJsonObject {
        base.forEach { (k, v) -> put(k, v) }
        putJsonObject("decision") { put("result", result); putJsonArray("reasons") { add(JsonPrimitive(reason)) }; putJsonArray("verifiedClaims") {}; put("policyVersion", "p"); put("fixtureDigest", "x"); put("isVerified", result == "Verified") }
    } }

    @Test fun `every decision is kept oldest first, each with what was measured, while only the latest is current`() {
        val events = listOf(
            e("RepairClaimed", mapOf("claimId" to "c1")), e("PhotoRecorded", mapOf("evidenceId" to "p1")),
            decision("Denied", "Repair has not been acknowledged by the technician"),
            e("TechnicianAcknowledged", mapOf("claimId" to "c1")),
            e("DiagnosticCompleted", mapOf("evidenceId" to "verify-diag-1")).let { d -> buildJsonObject { d.forEach { (k, v) -> put(k, v) }; putJsonObject("result") {
                put("fixtureId", "F"); put("diagnosticId", "D"); put("targetAlias", "a"); put("passed", true); put("completedAt", "2026-10-30T10:00:09Z"); put("source", "Simulated"); put("traceId", "t") } } },
            decision("Verified", "Qualifying fixture checks passed; this is not a live measurement"),
        )
        val history = attemptHistory(events, json)
        assertEquals(listOf(VerificationResult.Denied, VerificationResult.Verified), history.map { it.decision.result })
        assertEquals(listOf(0, 1), history.map { it.measured.size })
        assertEquals(DiagnosticSource.Simulated, history[1].measured.single().source)
        assertEquals(history[1].sequence, currentAttempt(events, json)!!.sequence)
    }

    private fun view(
        accepted: String? = null, acknowledged: Boolean = false, awaiting: List<String> = emptyList(),
        checklist: List<com.lensqi.fieldops.data.api.ChecklistEntry> = emptyList(), readiness: Readiness? = null,
        changes: List<VisualChangeAnalysis> = emptyList(),
    ) = InspectionView("I-1", "F", 1, "d", "A", InspectionStage.ReadyToVerify, 9, 0, currentClaimId = "c1", repairClaimedAt = "2026-10-30T10:00:00Z",
        technicianAcknowledged = acknowledged, photos = emptyList(), diagnostics = emptyList(), checklist = checklist, changeAnalyses = changes,
        acceptedPostRepairPhotoId = accepted, awaitingAnalysis = awaiting, readiness = readiness)
    private fun entry(req: String, status: ChecklistStatus, at: String = "2026-10-30T10:05:00Z", photo: String? = "p2") =
        com.lensqi.fieldops.data.api.ChecklistEntry(req, status, at, photo)
    private val required = listOf("switch_chassis_visible", "yellow_cable_near_end_visible")

    @Test fun `before the photo nothing is in place, and a review from before the claim does not count`() {
        val rows = verifyPreflight(view(checklist = listOf(entry("switch_chassis_visible", ChecklistStatus.Satisfied, at = "2026-10-30T09:00:00Z", photo = "p1"))), required)
        assertEquals(listOf("Repair photo", "Your review", "Your confirmation", "Equipment check"), rows.map { it.title })
        assertEquals(listOf(false, false, false, null), rows.map { it.ok })
    }

    @Test fun `an accepted photo, a full review and the confirmation show as in place, with the check measured on verify`() {
        val v = view(accepted = "p2", acknowledged = true, checklist = required.map { entry(it, ChecklistStatus.Satisfied) },
            changes = listOf(VisualChangeAnalysis("c1", "p2", "p1", "yellow cable", 12, null, null, null, VisualChangeVerdict.Changed, "moved", "v1")),
            readiness = Readiness(true, "Verify", emptyList(), listOf(ReadinessCheck("D", "2026-10-30T09:50:00Z", DiagnosticSource.Simulated, false, null, afterClaim = false)), measuresOnVerify = true))
        val rows = verifyPreflight(v, required)
        assertEquals(listOf(true, true, true, null), rows.map { it.ok })
        assertEquals(true, rows[0].detail.contains("change"))
        assertEquals(true, rows[3].detail.contains("Measured again") && rows[3].detail.contains("failed (simulated)"), rows[3].detail)
    }

    @Test fun `an answer given after the claim on an earlier accepted photo still counts, one on a rejected photo does not`() {
        val onEarlier = view(accepted = "p2", checklist = required.map { entry(it, ChecklistStatus.Satisfied, photo = "p1") },
            changes = listOf(VisualChangeAnalysis("c1", "p1", "p0", null, 12, null, null, null, VisualChangeVerdict.Changed, "moved", "v1"),
                VisualChangeAnalysis("c1", "p2", "p0", null, 12, null, null, null, VisualChangeVerdict.Changed, "moved", "v1")))
        assertEquals(true, verifyPreflight(onEarlier, required)[1].ok, "the policy accepts a review of an earlier accepted photo")
        val onRejected = view(accepted = "p2", checklist = required.map { entry(it, ChecklistStatus.Satisfied, photo = "p1") },
            changes = listOf(VisualChangeAnalysis("c1", "p1", "p0", null, 0, "p0", null, null, VisualChangeVerdict.DuplicateImage, "same", "v1")))
        assertEquals(false, verifyPreflight(onRejected, required)[1].ok, "a review of a photo the change check rejected does not count")
        val olderClaim = view(accepted = "p2", checklist = required.map { entry(it, ChecklistStatus.Satisfied, photo = "p1") },
            changes = listOf(VisualChangeAnalysis("c0", "p1", "p0", null, 0, "p0", null, null, VisualChangeVerdict.DuplicateImage, "same", "v1"),
                VisualChangeAnalysis("c1", "p1", "p0", null, 12, null, null, null, VisualChangeVerdict.Changed, "moved", "v1")))
        assertEquals(true, verifyPreflight(olderClaim, required)[1].ok, "a rejection under an earlier claim does not count against the current one")
    }

    @Test fun `a half review or a not-right answer is named, and a comparison still running is pending`() {
        val half = verifyPreflight(view(accepted = "p2", awaiting = emptyList(), checklist = listOf(entry("switch_chassis_visible", ChecklistStatus.Unknown))), required)
        assertNull(half[1].ok); assertEquals(true, half[1].detail.startsWith("1 of 2"))
        val wrong = verifyPreflight(view(accepted = "p2", checklist = listOf(entry("switch_chassis_visible", ChecklistStatus.NotSatisfied))), required)
        assertEquals(false, wrong[1].ok)
        assertNull(verifyPreflight(view(awaiting = listOf("p3")), required)[0].ok)
    }
}
