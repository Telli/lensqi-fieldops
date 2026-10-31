package com.lensqi.fieldops.domain

import com.lensqi.fieldops.data.api.ChecklistStatus
import com.lensqi.fieldops.data.api.DiagnosticResult
import com.lensqi.fieldops.data.api.InspectionStage
import com.lensqi.fieldops.data.api.InspectionView
import com.lensqi.fieldops.data.api.VerificationDecision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// The verify screen's reading of the server, kept in step with apps/web/src/lib/verify.ts. Nothing here decides a stage
// or an outcome: the card names the server's next step, and the attempt is read back from the event log.

/** Which card the verify screen shows. */
enum class VerifyCard(val step: Int) { Claim(0), Photo(0), Comparing(0), Review(1), Confirm(2), Run(3), Closed(4) }

/**
 * The card for the server's next step (readiness.next). An older server without readiness falls back to the stage and
 * the accepted photo; the server still refuses anything out of order.
 */
fun verifyCard(v: InspectionView): VerifyCard {
    v.readiness?.let { r ->
        return when (r.next) {
            "ClaimRepair" -> VerifyCard.Claim
            "CapturePhoto" -> VerifyCard.Photo
            "WaitForAnalysis" -> VerifyCard.Comparing
            "ReviewChecklist" -> VerifyCard.Review
            "Acknowledge" -> VerifyCard.Confirm
            "Verify" -> VerifyCard.Run
            else -> VerifyCard.Closed
        }
    }
    return when {
        v.stage == InspectionStage.VerifiedComplete || v.stage == InspectionStage.Inconclusive -> VerifyCard.Closed
        v.stage == InspectionStage.VerificationFailed || v.currentClaimId == null -> VerifyCard.Claim
        v.awaitingAnalysis.isNotEmpty() -> VerifyCard.Comparing
        v.acceptedPostRepairPhotoId == null || v.stage == InspectionStage.NeedsEvidence -> VerifyCard.Photo
        v.technicianAcknowledged -> VerifyCard.Run
        else -> VerifyCard.Review
    }
}

/**
 * What the camera can help with for the post-repair photo: the before photo the server compares against and the part
 * it compares. Only while the server's next step is CapturePhoto for a claim; any other step gets no capture help.
 */
data class CaptureGuide(val referencePhotoId: String, val concept: String?)

/**
 * The server names the reference in a change analysis once one exists for this claim. Before that, this is the client's
 * reading of the server's rule (the newest matched segmentation of a photo from before the claim); the guide is only a
 * framing aid, so a wrong guess costs the technician a less useful overlay, never a decision.
 */
fun captureGuide(v: InspectionView?): CaptureGuide? {
    if (v?.currentClaimId == null || verifyCard(v) != VerifyCard.Photo) return null
    v.changeAnalyses.lastOrNull { it.claimId == v.currentClaimId && it.referencePhotoEvidenceId != null }
        ?.let { return CaptureGuide(it.referencePhotoEvidenceId!!, it.targetPrompt) }
    val afterClaim = v.changeAnalyses.map { it.photoEvidenceId }.toSet() + v.awaitingAnalysis + listOfNotNull(v.acceptedPostRepairPhotoId)
    val reference = v.segmentations.lastOrNull { s -> s.matched && s.sourcePhotoEvidenceId !in afterClaim && v.photos.any { it.evidenceId == s.sourcePhotoEvidenceId } }
        ?: return null
    return CaptureGuide(reference.sourcePhotoEvidenceId, reference.concept)
}

data class AttemptReview(val requirementId: String, val status: ChecklistStatus, val photoEvidenceId: String?)

/** One verify call as the event log recorded it: the decision, what the person had confirmed, and what was measured. */
data class Attempt(
    val sequence: Long,
    val at: String,
    val decision: VerificationDecision,
    /** Fixture checks the verify call ran for this attempt; empty when the photo, review or confirmation decided first. */
    val measured: List<DiagnosticResult>,
    /** When the technician confirmed the evidence this attempt used. */
    val acknowledgedAt: String?,
    /** The checklist as it stood when the decision was made. */
    val review: List<AttemptReview>,
)

/** Evidence ids of checks the verify call ran itself (InspectionEndpoints.VerifyDiagnosticPrefix); a manual run is "diag-". */
const val VERIFY_DIAGNOSTIC_PREFIX = "verify-diag-"

/** Events the verify call records in one batch with its decision. */
private val sameCall = setOf("DiagnosticCompleted", "PredictionScored", "StageChanged")

/** Events that make an earlier decision stale for the screen: new evidence, a new review or confirmation, or a new claim. */
private val supersedes = setOf("RepairClaimed", "PhotoRecorded", "ChecklistReviewed", "TechnicianAcknowledged")

private fun JsonObject.str(key: String): String? = get(key)?.jsonPrimitive?.takeIf { it.isString }?.content
private val JsonObject.kind get() = str("kind")

/**
 * The decision for the evidence on screen now, or null when there is none: no verify yet, or a new claim, photo, review
 * or confirmation since. Measurements are only those recorded by that verify call, so a check run earlier by hand is
 * never shown as this attempt's.
 */
fun currentAttempt(events: List<JsonObject>, json: Json): Attempt? {
    var k = events.lastIndex
    while (k >= 0 && events[k].kind != "VerificationEvaluated") {
        if (events[k].kind in supersedes) return null
        k--
    }
    if (k < 0) return null
    val decided = events[k]
    val decision = decided["decision"]?.let { runCatching { json.decodeFromJsonElement(VerificationDecision.serializer(), it) }.getOrNull() } ?: return null
    val measured = ArrayDeque<DiagnosticResult>()
    var j = k - 1
    while (j >= 0 && events[j].kind in sameCall) {
        val e = events[j]
        if (e.kind == "DiagnosticCompleted" && e.str("evidenceId")?.startsWith(VERIFY_DIAGNOSTIC_PREFIX) == true)
            e["result"]?.let { runCatching { json.decodeFromJsonElement(DiagnosticResult.serializer(), it) }.getOrNull() }?.let(measured::addFirst)
        j--
    }
    val claimAt = (0 until k).lastOrNull { events[it].kind == "RepairClaimed" }
    val sinceClaim = events.subList(if (claimAt == null) 0 else claimAt + 1, k)
    val ack = sinceClaim.lastOrNull { it.kind == "TechnicianAcknowledged" }
    val review = LinkedHashMap<String, AttemptReview>()
    for (e in sinceClaim) if (e.kind == "ChecklistReviewed") {
        val req = e.str("requirementId") ?: continue
        val status = e.str("status")?.let { s -> ChecklistStatus.entries.firstOrNull { it.name == s } } ?: continue
        review[req] = AttemptReview(req, status, e.str("reviewedByEvidenceId"))
    }
    return Attempt(
        sequence = decided["sequence"]?.jsonPrimitive?.longOrNull ?: k.toLong(),
        at = decided.str("occurredAt").orEmpty(),
        decision = decision,
        measured = measured.toList(),
        acknowledgedAt = ack?.str("occurredAt"),
        review = review.values.toList(),
    )
}

/** What a check measured: the status a server answered with, else the closed-set value in words ("Reachable" → "reachable"). */
fun measuredValue(r: DiagnosticResult): String =
    r.httpStatus?.let { "HTTP $it" } ?: r.normalizedValue?.replace(Regex("([a-z])([A-Z])"), "$1 $2")?.lowercase() ?: "—"
