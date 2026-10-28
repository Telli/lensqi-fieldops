package com.lensqi.fieldops.ui.common

import com.lensqi.fieldops.data.api.InspectionStage
import com.lensqi.fieldops.data.api.InspectionSummary
import com.lensqi.fieldops.data.api.InspectionView
import com.lensqi.fieldops.data.api.ObservationStatus
import com.lensqi.fieldops.data.api.ProposedNextAction
import com.lensqi.fieldops.data.api.VisualAssessment

/** What the single primary button does next; each maps to a server command or a screen, nothing is decided here. */
enum class MoveKind { Scan, Assess, Check, Plan, Repair, Verify, Receipt, Retry }
data class Move(val kind: MoveKind, val title: String, val hint: String? = null)

private fun join(xs: List<String>) = if (xs.size <= 1) xs.firstOrNull().orEmpty() else xs.dropLast(1).joinToString(", ") + " and " + xs.last()

/** LensQi's one-sentence read, first person: the assessment's own findings before a claim, the verification after. */
fun aiSummary(v: InspectionView?, a: VisualAssessment?): String {
    if (v == null) return ""
    if (v.currentClaimId != null) {
        if (v.stage == InspectionStage.VerifiedComplete) return "The new photo and a fresh equipment check both passed."
        if (v.stage == InspectionStage.VerificationFailed) v.lastDecision?.reasons?.firstOrNull()?.let { return plainReason(it) }
        if (v.acceptedPostRepairPhotoId != null) return "I've compared your repair photo with the earlier one and accepted it."
        v.changeAnalyses.lastOrNull { it.claimId == v.currentClaimId }?.let { return changeText(it.verdict).detail }
        return "Show me the repaired connection so I can compare it with before."
    }
    if (v.photos.isEmpty()) return "Point the camera at the equipment and I'll tell you what I can see."
    if (a == null) return "I have your photo. Ask me to look at it."
    val hs = a.hypotheses.filterNot { isPostRepairItem(it.featureId) }
    val seen = hs.filter { it.status == ObservationStatus.Observed || it.status == ObservationStatus.Suspected }.map { featureName(it.featureId).lowercase() }
    val unclear = hs.filter { it.status == ObservationStatus.Unknown }.map { featureName(it.featureId).lowercase() }
    val odd = hs.filter { it.status == ObservationStatus.Conflicting }.map { featureName(it.featureId).lowercase() }
    return listOfNotNull(
        seen.takeIf { it.isNotEmpty() }?.let { "I can see the ${join(it)}." },
        unclear.takeIf { it.isNotEmpty() }?.let { "I can't make out the ${join(it)} yet." },
        odd.takeIf { it.isNotEmpty() }?.let { "The ${join(it)} doesn't match what I expect." },
    ).joinToString(" ").ifEmpty { "I couldn't make out the parts I need in this photo." }
}

/** The one thing to do next, from the server's stage and the latest assessment. */
fun nextMove(v: InspectionView?, a: VisualAssessment?): Move? {
    if (v == null) return null
    val asked = v.evidenceRequests.firstOrNull { it.answeredByPhotoId == null }
    when (v.stage) {
        InspectionStage.VerifiedComplete -> return Move(MoveKind.Receipt, "Verified complete", "The FieldProof receipt is ready.")
        InspectionStage.VerificationFailed -> return Move(MoveKind.Retry, "Still failing", "Check the connection again, then verify.")
        InspectionStage.AwaitingRepair -> return Move(MoveKind.Repair, "Do the repair", "Then tell me what you did.")
        InspectionStage.ReadyToVerify -> return Move(MoveKind.Verify, if (v.acceptedPostRepairPhotoId != null) "Review and verify" else "Photograph the repair", "A new photo and a fresh check both have to pass.")
        InspectionStage.FaultConfirmed, InspectionStage.FaultSuspected -> return Move(MoveKind.Plan, "Fault confirmed", "Say how you'll fix it.")
        else -> {}
    }
    if (v.currentClaimId != null) return Move(MoveKind.Verify, "Photograph the repair", "The same photo as before won't count.")
    if (asked != null) return Move(MoveKind.Scan, asked.note, "Requested by the ${asked.requestedBy.lowercase()}.")
    if (v.photos.isEmpty()) return Move(MoveKind.Scan, "Scan the equipment", "A wide photo first.")
    if (a == null) return Move(MoveKind.Assess, "Let LensQi look", "I'll describe the photo and outline the parts.")
    if (a.nextAction == ProposedNextAction.RequestView && !a.requestedView.isNullOrBlank()) return Move(MoveKind.Scan, sentence(a.requestedView), "I need this view to continue.")
    if (v.diagnostics.isEmpty()) return Move(MoveKind.Check, "Run the approved check", "It confirms the fault on the equipment itself.")
    return Move(MoveKind.Scan, "Take another look", "A new photo refreshes what I can see.")
}

/** Five coarse steps for the progress bar. */
fun progressStep(v: InspectionView?, a: VisualAssessment?): Int = when {
    v == null -> 0
    v.stage == InspectionStage.VerifiedComplete -> 5
    v.currentClaimId != null -> 4
    v.diagnostics.isNotEmpty() -> 3
    a != null -> 2
    v.photos.isNotEmpty() -> 1
    else -> 0
}
val progressSteps = listOf("Capture", "Observe", "Check", "Repair", "Verify")

/** What needs the technician, most urgent first; anything else is not on Today. */
fun needsYou(all: List<InspectionSummary>): List<InspectionSummary> {
    fun rank(s: InspectionSummary) = when {
        s.openEvidenceRequest != null -> 0
        s.stage == InspectionStage.VerificationFailed -> 1
        s.stage == InspectionStage.ReadyToVerify -> 2
        s.stage == InspectionStage.AwaitingRepair || s.stage == InspectionStage.FaultConfirmed -> 3
        s.stage == InspectionStage.NeedsEvidence && s.photos > 0 -> 4
        else -> 9
    }
    return all.filter { rank(it) < 9 }.sortedWith(compareBy<InspectionSummary> { rank(it) }.thenByDescending { it.updatedAt })
}

/** One line on why an inspection is on Today. */
fun reasonText(s: InspectionSummary): String = when {
    s.openEvidenceRequest != null -> s.openEvidenceRequest.note
    s.stage == InspectionStage.VerificationFailed -> "Failed. Check it again."
    s.stage == InspectionStage.ReadyToVerify -> "Repair claimed. Verify it."
    s.stage == InspectionStage.AwaitingRepair || s.stage == InspectionStage.FaultConfirmed -> s.workOrder ?: "Fault confirmed. Plan the fix."
    else -> "LensQi needs a better view."
}
