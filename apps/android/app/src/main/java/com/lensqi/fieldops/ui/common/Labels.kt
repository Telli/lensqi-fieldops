package com.lensqi.fieldops.ui.common

import com.lensqi.fieldops.data.api.ChecklistStatus
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
    VisualChangeVerdict.Changed -> ChangeText("Change detected", EvidenceState.HumanConfirmed, "Something in the target area changed since the earlier photo.")
    VisualChangeVerdict.NoVisualChange -> ChangeText("No change", EvidenceState.Failed, "The target area looks the same as before. Take a new photo after the repair.")
    VisualChangeVerdict.DuplicateImage -> ChangeText("Same photo", EvidenceState.Failed, "This is the same picture as an earlier one. Take a new photo after the repair.")
    VisualChangeVerdict.Unscorable -> ChangeText("Not compared", EvidenceState.Unknown, "There was no earlier outline of the part to compare against. Your review decides.")
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
    reason.startsWith("Diagnostic failed after the repair claim") -> "The equipment still fails its check after the repair."
    reason.contains("post-repair image") -> "A new photo taken after the repair is needed."
    reason.contains("acknowledged") -> "Confirm that you reviewed the evidence."
    reason.contains("has not been reviewed") -> "Review every checklist item against the new photo."
    reason.contains("not satisfied") -> "A checklist item is marked not right."
    reason.contains("unavailable") -> "The equipment check could not reach the endpoint."
    else -> reason
}

fun friendly(code: String, message: String) = when (code) {
    "offline" -> "Can't reach the inspection server. Your work is safe; try again when connected."
    "rate_limited" -> "Too many AI requests right now. Wait a minute and try again."
    "model_timeout" -> "The AI took too long to answer. Try again."
    "provider_unavailable" -> "The AI service is unavailable right now. Try again shortly."
    "image_too_small" -> "That photo is too small. Move closer or use a higher resolution."
    "change_evidence_rejected" -> "That photo wasn't accepted as post-repair evidence. Take a new one."
    else -> message
}
