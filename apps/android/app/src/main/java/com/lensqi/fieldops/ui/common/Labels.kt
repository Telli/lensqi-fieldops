package com.lensqi.fieldops.ui.common

import com.lensqi.fieldops.data.api.ChecklistStatus
import com.lensqi.fieldops.data.api.EvidenceOrigin
import com.lensqi.fieldops.data.api.InspectionStage
import com.lensqi.fieldops.data.api.ObservationStatus
import com.lensqi.fieldops.data.api.VisualChangeVerdict

/** Evidence states shown as badges; the design's vocabulary, mapped from server facts. */
enum class EvidenceState { Unknown, Hypothesis, EvidenceRequired, HumanConfirmed, Verified, Failed }

fun stageLabel(s: InspectionStage) = when (s) {
    InspectionStage.Created -> "Started"
    InspectionStage.InitialScan -> "Inspecting"
    InspectionStage.NeedsEvidence -> "Needs more evidence"
    InspectionStage.FaultSuspected -> "Fault suspected"
    InspectionStage.FaultConfirmed -> "Fault confirmed"
    InspectionStage.AwaitingRepair -> "Awaiting repair"
    InspectionStage.ReadyToVerify -> "Ready to verify"
    InspectionStage.VerificationFailed -> "Verification failed"
    InspectionStage.VerifiedComplete -> "Verified complete"
    InspectionStage.Inconclusive -> "Inconclusive"
}

fun stageState(s: InspectionStage) = when (s) {
    InspectionStage.Created, InspectionStage.Inconclusive -> EvidenceState.Unknown
    InspectionStage.InitialScan, InspectionStage.FaultSuspected -> EvidenceState.Hypothesis
    InspectionStage.NeedsEvidence, InspectionStage.ReadyToVerify -> EvidenceState.EvidenceRequired
    InspectionStage.FaultConfirmed, InspectionStage.AwaitingRepair -> EvidenceState.HumanConfirmed
    InspectionStage.VerificationFailed -> EvidenceState.Failed
    InspectionStage.VerifiedComplete -> EvidenceState.Verified
}

fun observationLabel(s: ObservationStatus) = when (s) {
    ObservationStatus.Observed -> EvidenceState.Hypothesis to "AI: visible"
    ObservationStatus.Suspected -> EvidenceState.Hypothesis to "AI hypothesis"
    ObservationStatus.Unknown -> EvidenceState.EvidenceRequired to "Can't see it yet"
    ObservationStatus.Conflicting -> EvidenceState.Failed to "Conflicting"
}

data class ChangeText(val label: String, val state: EvidenceState, val detail: String)

fun changeText(v: VisualChangeVerdict) = when (v) {
    VisualChangeVerdict.Changed -> ChangeText("Change detected", EvidenceState.Hypothesis, "Something in the target area changed since the earlier photo.")
    VisualChangeVerdict.NoVisualChange -> ChangeText("No change", EvidenceState.Failed, "The target area looks the same as before. Take a new photo after the repair.")
    VisualChangeVerdict.DuplicateImage -> ChangeText("Same photo", EvidenceState.Failed, "This is the same picture as an earlier one. Take a new photo after the repair.")
    VisualChangeVerdict.Unscorable -> ChangeText("Not compared", EvidenceState.Unknown, "The photos could not establish a change. Review the evidence; this is not a visual pass.")
}

fun checklistLabel(s: ChecklistStatus) = when (s) {
    ChecklistStatus.Satisfied -> "Looks right"
    ChecklistStatus.NotSatisfied -> "Not right"
    ChecklistStatus.Unknown -> "Can't tell"
    ChecklistStatus.Conflicting -> "Conflicting"
}

/** "yellow_cable_near_end_visible" → "Yellow cable near end". */
fun featureName(id: String) = id.replace(Regex("_(visible|captured|present)$"), "").replace('_', ' ').replaceFirstChar { it.uppercase() }

/** A before-repair photo can never show the post-repair view; that item is expected to be unknown. */
fun isPostRepairItem(id: String) = Regex("post_?repair", RegexOption.IGNORE_CASE).containsMatchIn(id)

fun sentence(t: String?) = t?.replaceFirstChar { it.uppercase() }.orEmpty()

/** Short banner sentence for a policy reason; the full reason stays in the technical details. */
fun plainReason(reason: String) = when {
    reason == "Specified live fixture checks passed" -> "The required live equipment checks passed."
    reason.startsWith("Qualifying ") && reason.endsWith("fixture checks passed; this is not a live measurement") -> "The required checks passed using a non-live source. This does not verify live equipment."
    reason.startsWith("Expected-state version mismatch") -> "The site record has changed. Ask the site administrator to review this inspection."
    reason == "No repair claim has been recorded" -> "Describe the repair before verifying it."
    reason.contains("still being compared") -> "The photos are still being compared. Wait for the result before continuing."
    reason.contains("latest photo was not accepted") || reason.startsWith("The last check sent the claim back") -> "A new repair photo is needed. Capture it before reviewing again."
    reason.contains("unrecognised review status") -> "A review answer couldn't be read. Review that item again."
    reason.startsWith("Fresh post-repair diagnostic") || reason.contains("is older than") -> "A fresh equipment check is required. Run verification again."
    reason.contains("not allowed by the fixture policy") -> "This check source isn't approved for the equipment. Ask the site administrator to review it."
    reason.contains("was run for fixture") -> "The check belongs to different equipment. Return to the correct inspection."
    reason.startsWith("Fixture declares no diagnostic") -> "The equipment has no approved check configured. Ask the site administrator to add one."
    reason.startsWith("Diagnostic failed after the repair claim") -> "The equipment still fails its check after the repair."
    reason.contains("post-repair image") -> "A new photo taken after the repair is needed."
    reason.contains("acknowledged") -> "Confirm that you reviewed the evidence."
    reason.contains("has not been reviewed") -> "Review every checklist item against the new photo."
    reason.contains("not satisfied") -> "A checklist item is marked not right."
    reason.contains("remains unknown") || reason.contains("conflicting") -> "A checklist item needs a person to decide."
    reason.contains("unavailable") -> "The equipment check is unavailable. Try it again when connected."
    reason.contains("change analysis rejected") -> "The reviewed photo was not accepted. Review against the new photo."
    else -> "A required check is unresolved. Review the evidence and try again."
}

@Suppress("UNUSED_PARAMETER")
fun friendly(code: String, message: String): String = when (code) {
    "offline" -> "Can't connect. Reconnect, then refresh to check whether your last action was saved."
    "rate_limited", "http_429" -> "Analysis paused briefly. Wait a moment, then try again."
    "model_timeout", "provider_unavailable", "invalid_model_output" -> "Analysis couldn't finish. Try again shortly."
    "image_too_small" -> "The photo needs more detail. Take a new photo at full resolution."
    "change_evidence_rejected" -> "The repair photo wasn't accepted. Take a fresh photo after the repair."
    "unsafe_plan", "safety_restricted" -> "This task needs qualified help. Stop and review the safety instructions."
    "client_contract_mismatch" -> "This response couldn't be read. Refresh, or check for an app update."
    // invalid_state also covers work that is already done, so it doesn't presume a check is missing.
    "invalid_state" -> "This action doesn't fit the current state. Refresh and review where things stand."
    "stale_evidence", "verification_not_ready" -> "This action isn't available yet. Refresh and complete the required checks."
    "local_save_failed" -> "The photo couldn't be saved. Check free space, then take it again."
    else -> "This action couldn't be completed. Refresh to check its status before trying again."
}

/** Where a photo came from, as the technician reads it. Mock glasses are never called glasses. */
fun originLabel(o: EvidenceOrigin) = when (o) {
    EvidenceOrigin.Phone -> "Camera"
    EvidenceOrigin.Glasses -> "Glasses"
    EvidenceOrigin.MockWearables -> "Mock glasses"
    else -> "Imported"
}
