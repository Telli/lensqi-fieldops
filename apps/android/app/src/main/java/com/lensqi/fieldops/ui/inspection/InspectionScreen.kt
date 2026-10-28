package com.lensqi.fieldops.ui.inspection

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.HighlightOff
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lensqi.fieldops.data.api.ChecklistStatus
import com.lensqi.fieldops.data.api.DiagnosticSource
import com.lensqi.fieldops.data.api.EvidenceOrigin
import com.lensqi.fieldops.data.api.InspectionStage
import com.lensqi.fieldops.data.api.InspectionView
import com.lensqi.fieldops.data.api.ObservationStatus
import com.lensqi.fieldops.data.api.ProposedNextAction
import com.lensqi.fieldops.data.api.VerificationOutcomeKind
import com.lensqi.fieldops.data.api.VerificationResult
import com.lensqi.fieldops.data.queue.CommandState
import com.lensqi.fieldops.ui.common.CheckRow
import com.lensqi.fieldops.ui.common.Dot
import com.lensqi.fieldops.ui.common.EvidenceBadge
import com.lensqi.fieldops.ui.common.Expandable
import com.lensqi.fieldops.ui.common.Panel
import com.lensqi.fieldops.ui.common.PrimaryButton
import com.lensqi.fieldops.ui.common.SecondaryButton
import com.lensqi.fieldops.ui.common.SectionTitle
import com.lensqi.fieldops.ui.common.SimBadge
import com.lensqi.fieldops.ui.common.SourceBadge
import com.lensqi.fieldops.ui.common.Tag
import com.lensqi.fieldops.ui.common.TechDetails
import com.lensqi.fieldops.ui.common.changeText
import com.lensqi.fieldops.ui.common.checklistLabel
import com.lensqi.fieldops.ui.common.featureName
import com.lensqi.fieldops.ui.common.isPostRepairItem
import com.lensqi.fieldops.ui.common.observationLabel
import com.lensqi.fieldops.ui.common.plainReason
import com.lensqi.fieldops.ui.common.sentence
import com.lensqi.fieldops.ui.common.stageLabel
import com.lensqi.fieldops.ui.common.stageState
import com.lensqi.fieldops.ui.home.whenText
import com.lensqi.fieldops.ui.theme.Eyebrow
import com.lensqi.fieldops.ui.theme.Fo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val DIAG_FAILED = "Diagnostic failed after the repair claim"

/**
 * One inspection in the design's two modes: "Investigation" until a repair is claimed, then "Repair verification".
 * Stage, verdicts and decisions are the server's; the screen only sends commands and shows what comes back.
 */
@Composable
fun InspectionScreen(onBack: () -> Unit, onCapture: () -> Unit, onOpenReceipt: (url: String) -> Unit, onReview: () -> Unit = {}, vm: InspectionViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { vm.captureForReview(copyToCache(context, uri), origin = "RecordedFixture"); onReview() }
    }
    val importPhoto = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val view = s.view
    val verifying = view?.currentClaimId != null

    Column {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text(if (verifying) "Repair verification" else "Investigation", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            view?.let { EvidenceBadge(stageState(it.stage), stageLabel(it.stage)) }
            Spacer(Modifier.width(12.dp))
        }
        HorizontalDivider(color = Fo.Border)
        LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Spacer(Modifier.height(2.dp)) }
            s.viewCachedAt?.let { at ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.CloudOff, null, tint = Fo.Warning, modifier = Modifier.size(16.dp))
                        Text("Offline · showing the server's view from ${whenText(at)}. Nothing new is decided until it answers.", color = Fo.Warning, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            s.busy?.let { label ->
                item {
                    Column {
                        Text(label, style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp), color = Fo.Primary, trackColor = Fo.Border)
                        TextButton(onClick = vm::cancel) { Text("Cancel") }
                    }
                }
            }
            s.error?.let { item { Text(it, color = Fo.Failure, style = MaterialTheme.typography.bodySmall) } }
            if (view != null) {
                val asset = s.fixture?.assets?.firstOrNull { it.assetId == view.assetId }
                item { Text("${asset?.displayName ?: view.assetId} · ${view.fixtureId}", style = MaterialTheme.typography.bodySmall) }
                if (verifying) verificationItems(s, vm, view, onCapture, importPhoto, onOpenReceipt)
                else investigationItems(s, vm, view, onCapture, importPhoto)
                item {
                    TechDetails(
                        listOf(
                            "Inspection" to view.inspectionId, "Fixture" to "${view.fixtureId} rev ${view.fixtureRevision}", "Fixture digest" to view.fixtureDigest,
                            "AI model" to (s.latestAssessment?.modelId ?: "—"), "Events" to "${view.lastSequence}",
                        ) + (view.lastDecision?.let { listOf("Decision" to it.result.name, "Reason" to it.reasons.joinToString(" "), "Policy" to it.policyVersion) } ?: emptyList()),
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun LazyListScope.investigationItems(s: InspectionUi, vm: InspectionViewModel, view: InspectionView, onCapture: () -> Unit, importPhoto: () -> Unit) {
    val a = s.latestAssessment
    val relevant = a?.hypotheses?.filterNot { isPostRepairItem(it.featureId) }.orEmpty()
    val unclear = relevant.filter { it.status != ObservationStatus.Observed }
    val needsView = a?.nextAction == ProposedNextAction.RequestView && view.stage == InspectionStage.NeedsEvidence
    val diag = view.diagnostics.lastOrNull()
    val canRepair = view.stage in listOf(InspectionStage.FaultConfirmed, InspectionStage.FaultSuspected, InspectionStage.AwaitingRepair)
    val done = listOf(a != null, a != null, a != null && !needsView, s.outlines.isNotEmpty(), diag != null, false)
    val active = done.indexOfFirst { !it }

    item {
        Step(1, "Initial observation", done[0], active == 0) {
            val visible = relevant.size - unclear.size
            Text(
                when {
                    a != null -> "LensQi looked at ${view.photos.size} photo${if (view.photos.size == 1) "" else "s"}: $visible part${if (visible == 1) "" else "s"} visible" + if (unclear.isNotEmpty()) ", ${unclear.size} unclear." else "."
                    view.photos.isEmpty() -> "Capture a wide photo of the equipment."
                    else -> "Photo saved. Ask LensQi to look at it."
                },
            )
            PhotoStrip(s, vm)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Capture", onCapture, Modifier.weight(1f), icon = Icons.Outlined.CameraAlt)
                SecondaryButton("Import", importPhoto, Modifier.weight(1f), icon = Icons.Outlined.Image)
            }
            if (view.photos.isNotEmpty()) SecondaryButton(if (a == null) "Ask LensQi to look" else "Look at the latest photo again", vm::assessAndOutline, Modifier.fillMaxWidth(), enabled = s.busy == null, icon = Icons.Outlined.Visibility)
        }
    }
    item {
        Step(2, "Hypothesis", done[1], active == 1) {
            if (a == null) Muted("Waiting for the first observation.")
            relevant.forEach { h ->
                val (st, label) = observationLabel(h.status)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text(featureName(h.featureId), fontWeight = FontWeight.Medium); EvidenceBadge(st, label) }
                Muted(h.reason)
            }
            if (a != null) Muted("AI suggestions, not evidence. Your review and the equipment check decide.")
        }
    }
    item {
        Step(3, "Evidence requested", done[2], active == 2) {
            if (needsView) {
                Text(sentence(a?.requestedView))
                Text("Required to continue", color = Fo.Warning, style = MaterialTheme.typography.bodySmall)
                PrimaryButton("Capture another view", onCapture, Modifier.fillMaxWidth(), icon = Icons.Outlined.CameraAlt)
            } else Muted(
                when {
                    a == null -> "—"
                    diag == null -> "No extra photo needed. Run the equipment check next to confirm the fault."
                    else -> "No extra photo needed right now."
                },
            )
        }
    }
    item {
        Step(4, "Parts outlined", done[3], active == 3) {
            val img = s.outlineImage
            when {
                s.outlines.isNotEmpty() && img != null && s.outlinePhotoId == view.photos.lastOrNull()?.evidenceId -> {
                    Muted("AI outlines: ${s.outlines.joinToString { it.concept }}. Tap a label for the AI's reason.")
                    Box(Modifier.clip(RoundedCornerShape(12.dp))) {
                        ArOverlay(img, s.outlines, arTags(s.fixture, a, s.outlines), guide = if (needsView) targetConcept(s.fixture, a) else null)
                    }
                }
                a != null -> Muted("No part could be outlined in the latest photo. Nothing was guessed.")
                else -> Muted("Outlined automatically when LensQi looks at a photo.")
            }
        }
    }
    item { Step(5, "Equipment check", done[4], active == 4) { EquipmentCheck(s, vm, view) } }
    item {
        Step(6, "Repair", false, active == 5 || canRepair, last = true) {
            when {
                canRepair && view.stage != InspectionStage.AwaitingRepair -> {
                    Muted("Plan the fix, do it, then tell LensQi it's done.")
                    Field(s.workOrderText, vm::onWorkOrderChanged, "Plan")
                    SecondaryButton("Save plan", { vm.workOrder(s.workOrderText) }, Modifier.fillMaxWidth(), enabled = s.busy == null && s.workOrderText.isNotBlank())
                }
                view.stage == InspectionStage.AwaitingRepair -> {
                    Field(s.claimText, vm::onClaimChanged, "What did you do?")
                    PrimaryButton("Repair completed — verify", { vm.claim(s.claimText) }, Modifier.fillMaxWidth(), enabled = s.busy == null && s.claimText.isNotBlank(), icon = Icons.Outlined.Build)
                }
                else -> Muted("Available once the fault is confirmed.")
            }
        }
    }
    item { Knowledge(s) }
}

private fun LazyListScope.verificationItems(s: InspectionUi, vm: InspectionViewModel, view: InspectionView, onCapture: () -> Unit, importPhoto: () -> Unit, onOpenReceipt: (String) -> Unit) {
    val accepted = view.photos.firstOrNull { it.evidenceId == view.acceptedPostRepairPhotoId }
    val change = view.changeAnalyses.lastOrNull { it.claimId == view.currentClaimId }
    val acceptedChange = accepted?.let { p -> view.changeAnalyses.lastOrNull { it.photoEvidenceId == p.evidenceId } }
    val required = s.fixture?.visualRequirements?.filter { it.mandatory }.orEmpty()
    fun reviewed(req: String) = view.checklist.firstOrNull { it.requirementId == req && (it.reviewedByEvidenceId == null || it.reviewedByEvidenceId == accepted?.evidenceId) }
    val allReviewed = required.all { reviewed(it.requirementId) != null }
    val decided = view.stage == InspectionStage.VerificationFailed || view.stage == InspectionStage.VerifiedComplete
    val d = if (decided) view.lastDecision else null
    val diag = view.diagnostics.lastOrNull()

    if (!decided) {
        item {
            Panel {
                Text("FieldProof runs two independent checks", style = MaterialTheme.typography.titleMedium)
                Muted("A new photo that you've reviewed, and a fresh equipment check. Both must pass; a photo alone can't mark this work complete.")
            }
        }
        item { SectionTitle("1 · New photo after the repair") }
        item {
            if (accepted != null) {
                Panel(padding = 10) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        vm.artifactFor(accepted.evidenceId)?.let { AsyncImage(vm.artifactUrl(it), "Post-repair photo", Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop) }
                        Column(Modifier.weight(1f)) {
                            Text("Photo accepted", fontWeight = FontWeight.Medium)
                            Muted("${whenText(accepted.observedAt)} · ${acceptedChange?.let { changeText(it.verdict).label } ?: "not compared"}")
                        }
                        TextButton(onClick = onCapture) { Text("Retake") }
                    }
                }
            } else {
                Panel(border = if (change != null) Fo.Failure.copy(alpha = 0.4f) else Fo.Border, background = if (change != null) Fo.Failure.copy(alpha = 0.1f) else Fo.Surface) {
                    if (change != null) {
                        val t = changeText(change.verdict)
                        EvidenceBadge(t.state, t.label)
                        Text(t.detail)
                    } else Muted("Take a photo of the repaired connection.")
                    PendingUploads(s)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("Take photo", onCapture, Modifier.weight(1f), icon = Icons.Outlined.CameraAlt)
                        SecondaryButton("Import", importPhoto, Modifier.weight(1f), icon = Icons.Outlined.Image)
                    }
                }
            }
        }
        item { SectionTitle("2 · Your review") }
        item {
            Panel(padding = 12) {
                required.forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider(color = Fo.Border)
                    Text(featureName(r.requirementId), color = if (accepted != null) Fo.Foreground else Fo.Muted)
                    val cur = reviewed(r.requirementId)?.status
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(ChecklistStatus.Satisfied, ChecklistStatus.NotSatisfied, ChecklistStatus.Unknown).forEach { st ->
                            val tone = when (st) { ChecklistStatus.Satisfied -> Fo.Success; ChecklistStatus.NotSatisfied -> Fo.Failure; else -> Fo.Warning }
                            val on = cur == st
                            Box(
                                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(8.dp))
                                    .background(if (on) tone.copy(alpha = 0.15f) else Color.Transparent)
                                    .border(1.dp, if (on) tone.copy(alpha = 0.5f) else Fo.Border, RoundedCornerShape(8.dp))
                                    .clickable(enabled = accepted != null && s.busy == null) { accepted?.let { vm.review(r.requirementId, st, it.evidenceId) } },
                                contentAlignment = Alignment.Center,
                            ) { Text(checklistLabel(st), color = if (on) tone else Fo.Muted.copy(alpha = if (accepted != null) 1f else 0.4f), fontSize = 12.sp, fontWeight = FontWeight.Medium) }
                        }
                    }
                }
            }
        }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("3 · I reviewed this evidence myself", modifier = Modifier.weight(1f), color = if (allReviewed) Fo.Foreground else Fo.Muted)
                    Switch(
                        view.technicianAcknowledged, { vm.acknowledge() }, enabled = allReviewed && !view.technicianAcknowledged && s.busy == null,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Fo.Primary, checkedThumbColor = Fo.OnPrimary,
                            disabledCheckedTrackColor = Fo.Primary.copy(alpha = 0.6f), disabledCheckedThumbColor = Fo.OnPrimary,
                        ),
                    )
                }
            }
        }
        item {
            val ready = view.stage == InspectionStage.ReadyToVerify && accepted != null && allReviewed && view.technicianAcknowledged
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PrimaryButton("Run verification", vm::verify, Modifier.fillMaxWidth(), enabled = ready && s.busy == null, busy = s.busy?.startsWith("Server is verifying") == true, icon = Icons.Outlined.Build)
                if (!ready) Muted(
                    when {
                        accepted == null -> "Take a post-repair photo first."
                        !allReviewed -> "Review each item against the new photo."
                        !view.technicianAcknowledged -> "Confirm you reviewed the evidence."
                        else -> "Waiting for the server to accept the evidence."
                    },
                )
            }
        }
    }

    if (d != null) {
        val reason = d.reasons.firstOrNull().orEmpty()
        val visual = d.result == VerificationResult.Verified || reason.startsWith(DIAG_FAILED) || reason.contains("unavailable")
        val equipment = if (d.result == VerificationResult.Verified) true else if (visual) false else null
        item { CheckRow("Visual verification", if (visual) "New photo accepted and every item reviewed." else plainReason(reason), visual) }
        item {
            CheckRow(
                "Equipment check",
                when (equipment) {
                    null -> "Not run: the visual check has to pass first."
                    true -> "Endpoint reachable · ${whenText(diag?.result?.completedAt)}"
                    false -> "Endpoint still failing · ${whenText(diag?.result?.completedAt)}"
                },
                equipment,
            )
        }
        if (d.result == VerificationResult.Verified) item {
            Outcome(Fo.Success, Icons.Outlined.VerifiedUser, "Verified complete", "All required checks passed.") {
                if (d.outcomeKind == VerificationOutcomeKind.VerifiedCompleteSimulated)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { SimBadge(); Muted("The equipment check was simulated, not a live measurement.") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOfNotNull(view.photos.firstOrNull()?.let { "Before" to it }, accepted?.let { "After" to it }).forEach { (label, p) ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            vm.artifactFor(p.evidenceId)?.let { AsyncImage(vm.artifactUrl(it), label, Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop) }
                            Text(label.uppercase(), style = Eyebrow)
                        }
                    }
                }
                PrimaryButton("View FieldProof receipt", { onOpenReceipt(vm.receiptUrl()) }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Receipt)
                SecondaryButton("Check receipt integrity", vm::checkReceipt, Modifier.fillMaxWidth(), enabled = s.busy == null)
                s.receiptCheck?.let { r ->
                    Text(if (r.isValid) "Intact · ${r.checks.size} checks passed" else "Tampered: ${r.failures.joinToString()}", color = if (r.isValid) Fo.Success else Fo.Failure, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else item {
            Outcome(Fo.Failure, Icons.Outlined.GppBad, "Verification failed", plainReason(reason)) {
                Muted("This work can't be marked verified. Every attempt is kept in the record.")
                Field(s.claimText, vm::onClaimChanged, "What did you do this time?")
                PrimaryButton("Fixed it again — verify", { vm.claim(s.claimText) }, Modifier.fillMaxWidth(), enabled = s.busy == null && s.claimText.isNotBlank(), icon = Icons.Outlined.Refresh)
            }
        }
    }
    if (s.attempts.isNotEmpty()) item {
        Muted("Attempts: " + s.attempts.joinToString("  ") { if (it == VerificationResult.Verified) "✓" else "✕" } + " — every attempt is kept in the audit record.")
    }
    if (view.stage != InspectionStage.VerifiedComplete) item { Expandable("Equipment check") { EquipmentCheck(s, vm, view) } }
}

@Composable
private fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = Fo.Muted)

@Composable
private fun Step(n: Int, title: String, done: Boolean, active: Boolean, last: Boolean = false, content: @Composable () -> Unit) {
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxHeight()) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(if (done) Fo.Primary else Color.Transparent)
                    .border(1.dp, if (done) Fo.Primary else if (active) Fo.Warning else Fo.Border, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("$n", fontSize = 11.sp, color = if (done) Fo.OnPrimary else if (active) Fo.Warning else Fo.Muted) }
            if (!last) Box(Modifier.padding(top = 4.dp).width(1.dp).weight(1f).background(if (done) Fo.Primary.copy(alpha = 0.5f) else Fo.Border))
        }
        Column(Modifier.weight(1f).padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title.uppercase(), style = Eyebrow)
            content()
        }
    }
}

@Composable
private fun Outcome(color: Color, icon: ImageVector, title: String, body: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.1f), RoundedCornerShape(16.dp)).border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(16.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(40.dp))
        Text(title, color = color, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(body)
        content()
    }
}

@Composable
private fun PhotoStrip(s: InspectionUi, vm: InspectionViewModel) {
    val view = s.view ?: return
    if (view.photos.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        view.photos.forEach { p ->
            Column(Modifier.width(120.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                vm.artifactFor(p.evidenceId)?.let { AsyncImage(vm.artifactUrl(it), null, Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop) }
                Muted(if (p.origin == EvidenceOrigin.Phone) "Camera · ${whenText(p.observedAt)}" else "Imported · ${whenText(p.observedAt)}")
            }
        }
    }
    PendingUploads(s)
}

@Composable
private fun PendingUploads(s: InspectionUi) {
    s.pending.forEach { c ->
        val rejected = c.state == CommandState.Rejected
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Dot(if (rejected) Fo.Failure else Fo.Warning)
            Text(
                if (rejected) "Not uploaded: ${c.errorMessage}" else "Awaiting upload · saved on this phone",
                color = if (rejected) Fo.Failure else Fo.Warning, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun EquipmentCheck(s: InspectionUi, vm: InspectionViewModel, view: InspectionView) {
    val check = s.fixture?.diagnostics?.firstOrNull()
    val r = view.diagnostics.lastOrNull()?.result
    if (r != null) {
        val (color, icon, text) = when (r.passed) {
            true -> Triple(Fo.Success, Icons.Outlined.CheckCircle, "Pass — endpoint reachable")
            false -> Triple(Fo.Failure, Icons.Outlined.HighlightOff, "Fail — endpoint unreachable")
            null -> Triple(Fo.Warning, Icons.Outlined.RadioButtonUnchecked, "No answer — check unavailable")
        }
        Panel(border = color.copy(alpha = 0.4f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
                Text(text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                SourceBadge(r.source)
            }
            Muted("${whenText(r.completedAt)}${r.normalizedValue?.let { " · $it" } ?: ""}")
            check?.claimVerified?.let { Muted("What a pass proves: $it") }
        }
    } else Muted("Run the approved check to confirm the fault.")
    check?.let { c ->
        SecondaryButton("Run approved check", { vm.runDiagnostic(c.diagnosticId) }, Modifier.fillMaxWidth(), enabled = s.busy == null, icon = Icons.Outlined.PlayArrow)
        Muted("Only checks the site approved for this equipment; the target is set by the site, not the app.")
        if (s.diagnosticsMode == DiagnosticSource.Simulated) {
            Row(Modifier.fillMaxWidth().border(1.dp, Fo.Border, RoundedCornerShape(10.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Demo: simulated endpoint reachable", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Muted("Stands in for the lab endpoint; its results are always labelled Simulated.")
                }
                Switch(s.simUp == true, { vm.setSimulated(c.diagnosticId, it) }, enabled = s.busy == null, colors = SwitchDefaults.colors(checkedTrackColor = Fo.Primary, checkedThumbColor = Fo.OnPrimary))
            }
        }
    }
}

@Composable
private fun Knowledge(s: InspectionUi) {
    val k = s.knowledge ?: return
    Expandable("Vendor knowledge") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (s.knowledgeCachedAt != null) Tag("CACHED", Fo.Warning)
            Muted(if (k.packId != null) "${k.facts.size} sourced facts" else "No documentation pack for this equipment")
        }
        s.knowledgeCachedAt?.let { Muted("Copy saved on this phone at ${whenText(it)} · pack ${k.packSha256?.take(12)}…") }
        Muted("Guidance with sources. Documentation never proves a repair worked.")
        k.facts.forEach { f ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Dot(Fo.Primary)
                    Text(if (f.provenance == "SiteConfigured") "Site setup" else "Vendor documentation", style = Eyebrow)
                }
                Text(f.statement, fontSize = 13.sp)
                Muted("${f.citation.documentTitle} — ${f.citation.sourceLocator}")
            }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Fo.Primary, unfocusedBorderColor = Fo.Border),
    )
}

private suspend fun copyToCache(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
    val file = File(context.cacheDir, "import-${System.nanoTime()}")
    context.contentResolver.openInputStream(uri).use { input -> file.outputStream().use { requireNotNull(input).copyTo(it) } }
    file
}
