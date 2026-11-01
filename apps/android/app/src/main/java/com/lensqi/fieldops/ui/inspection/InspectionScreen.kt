package com.lensqi.fieldops.ui.inspection

import com.lensqi.fieldops.ui.common.TechnicianText

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.outlined.AutoAwesome
import com.lensqi.fieldops.ui.common.AiCard
import com.lensqi.fieldops.ui.common.MoveKind
import com.lensqi.fieldops.ui.common.ProgressSegments
import com.lensqi.fieldops.ui.common.aiSummary
import com.lensqi.fieldops.ui.common.nextMove
import com.lensqi.fieldops.ui.common.progressStep
import com.lensqi.fieldops.ui.theme.FoDark
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
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
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lensqi.fieldops.data.api.PhotoEvidence
import com.lensqi.fieldops.data.api.VisualChangeVerdict
import com.lensqi.fieldops.domain.Attempt
import com.lensqi.fieldops.domain.VerifyCard
import com.lensqi.fieldops.domain.measuredValue
import com.lensqi.fieldops.domain.verifyCard
import androidx.compose.material.icons.outlined.Speed
import com.lensqi.fieldops.ui.common.verifySteps
import java.time.Instant
import java.time.OffsetDateTime
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
import com.lensqi.fieldops.ui.common.originLabel
import com.lensqi.fieldops.data.api.InspectionStage
import com.lensqi.fieldops.data.api.InspectionView
import com.lensqi.fieldops.data.api.ObservationStatus
import com.lensqi.fieldops.data.api.ProposedNextAction
import com.lensqi.fieldops.data.api.VerificationOutcomeKind
import com.lensqi.fieldops.data.api.VerificationResult
import com.lensqi.fieldops.data.queue.CommandState
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

/**
 * One inspection in the design's two modes: "Investigation" until a repair is claimed, then "Repair verification".
 * Stage, verdicts and decisions are the server's; the screen only sends commands and shows what comes back.
 */
@Composable
fun InspectionScreen(onBack: () -> Unit, onCapture: () -> Unit, onOpenReceipt: (url: String) -> Unit, onReview: () -> Unit = {}, onGlasses: () -> Unit = {}, vm: InspectionViewModel = hiltViewModel()) {
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
            Column(Modifier.weight(1f)) {
                // The equipment is the title; whether this is verification shows in the stage badge and the AI card.
                val name = view?.let { v -> s.fixture?.assets?.firstOrNull { it.assetId == v.assetId }?.displayName ?: v.assetId }
                Text(name ?: "Inspection", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                if (verifying) Text("Repair verification", style = MaterialTheme.typography.bodySmall)
            }
            view?.let { EvidenceBadge(stageState(it.stage), stageLabel(it.stage)) }
            Spacer(Modifier.width(12.dp))
        }
        HorizontalDivider(color = Fo.Border)
        val list = rememberLazyListState()
        BringNextStepIntoView(list, view?.takeIf { verifying }?.let { verifyCard(it) to s.attempt?.sequence })
        LazyColumn(Modifier.padding(horizontal = 16.dp), state = list, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Spacer(Modifier.height(2.dp)) }
            s.viewCachedAt?.let { at ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.CloudOff, null, tint = Fo.Warning, modifier = Modifier.size(16.dp))
                        Text("Offline · last updated ${whenText(at)}. Reconnect to verify new work.", color = Fo.Warning, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            s.busy?.let { label ->
                item {
                    Column {
                        Text(TechnicianText.guidance(label), style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp), color = Fo.Primary, trackColor = Fo.Border)
                        TextButton(onClick = vm::cancel) { Text("Cancel") }
                    }
                }
            }
            s.error?.let { item { Text(it, color = Fo.Failure, style = MaterialTheme.typography.bodySmall) } }
            if (view != null) {
                val asset = s.fixture?.assets?.firstOrNull { it.assetId == view.assetId }
                if (verifying) verificationItems(s, vm, view, onCapture, importPhoto, onOpenReceipt, onGlasses)
                else investigationItems(s, vm, view, onCapture, importPhoto, onGlasses)
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

private fun LazyListScope.investigationItems(s: InspectionUi, vm: InspectionViewModel, view: InspectionView, onCapture: () -> Unit, importPhoto: () -> Unit, onGlasses: () -> Unit) {
    val a = s.latestAssessment
    val findings = a?.hypotheses?.filterNot { isPostRepairItem(it.featureId) }.orEmpty()
    val move = nextMove(view, a)
    val latest = view.photos.lastOrNull()
    val target = targetConcept(s.fixture, a)

    // Hero: the latest photo with LensQi's outlines, and the camera one tap away.
    item {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(FoDark.Overlay)) {
            val img = s.outlineImage
            when {
                img != null && s.outlinePhotoId == latest?.evidenceId -> ArOverlay(img, s.outlines, arTags(s.fixture, a, s.outlines), guide = target)
                latest != null -> vm.artifactFor(latest.evidenceId)?.let { AsyncImage(vm.artifactUrl(it), "Latest photo", Modifier.fillMaxWidth().aspectRatio(4f / 3f), contentScale = ContentScale.Crop) }
                else -> Column(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clickable(onClick = onCapture), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Outlined.CameraAlt, null, tint = FoDark.Muted, modifier = Modifier.size(36.dp))
                    Text("Tap to open the camera", color = FoDark.Muted, fontSize = 13.sp)
                }
            }
            if (latest != null) Box(
                Modifier.align(Alignment.BottomEnd).padding(12.dp).size(46.dp).clip(CircleShape).background(FoDark.Overlay.copy(alpha = 0.7f)).clickable(onClick = onCapture),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.CameraAlt, "New photo", tint = FoDark.Foreground) }
            if (view.photos.size > 1) Text("${view.photos.size} photos", fontSize = 11.sp, color = FoDark.Foreground,
                modifier = Modifier.padding(12.dp).clip(RoundedCornerShape(50)).background(FoDark.Overlay.copy(alpha = 0.65f)).padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
    // One AI card: what LensQi sees, the next move, the single action.
    item {
        AiCard(aiSummary(view, a), move?.title, move?.hint) {
            when (move?.kind) {
                MoveKind.Scan -> {
                    PrimaryButton("Open camera", onCapture, Modifier.fillMaxWidth(), icon = Icons.Outlined.CameraAlt)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Import", importPhoto, Modifier.weight(1f), icon = Icons.Outlined.Image)
                        SecondaryButton("Glasses", onGlasses, Modifier.weight(1f), icon = Icons.Outlined.Visibility)
                    }
                }
                MoveKind.Assess -> PrimaryButton("Look at the photo", vm::assessAndOutline, Modifier.fillMaxWidth(), enabled = s.busy == null, busy = s.busy != null, icon = Icons.Outlined.AutoAwesome)
                MoveKind.Check -> s.fixture?.diagnostics?.firstOrNull()?.let { c ->
                    PrimaryButton("Run the check", { vm.runDiagnostic(c.diagnosticId) }, Modifier.fillMaxWidth(), enabled = s.busy == null, busy = s.busy != null, icon = Icons.Outlined.PlayArrow)
                }
                MoveKind.Plan -> {
                    Field(s.workOrderText, vm::onWorkOrderChanged, "How will you fix it?")
                    PrimaryButton("Save plan", { vm.workOrder(s.workOrderText) }, Modifier.fillMaxWidth(), enabled = s.busy == null && s.workOrderText.isNotBlank(), icon = Icons.Outlined.Build)
                }
                MoveKind.Repair -> {
                    Field(s.claimText, vm::onClaimChanged, "What did you do?")
                    PrimaryButton("Repair done — verify", { vm.claim(s.claimText) }, Modifier.fillMaxWidth(), enabled = s.busy == null && s.claimText.isNotBlank(), icon = Icons.Outlined.Build)
                }
                else -> {}
            }
        }
    }
    item { ProgressSegments(progressStep(view, a)) }
    if (findings.isNotEmpty()) item {
        Expandable("What LensQi found") {
            findings.forEach { h ->
                val (st, label) = observationLabel(h.status)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text(featureName(h.featureId), fontWeight = FontWeight.Medium); EvidenceBadge(st, label) }
                Muted(h.reason)
            }
            Muted("Suggestions, not evidence. Your review and the equipment check decide.")
        }
    }
    item { Expandable("Equipment check") { EquipmentCheck(s, vm, view) } }
    if (view.photos.isNotEmpty()) item {
        Expandable("Photos · ${view.photos.size}") {
            PhotoStrip(s, vm)
            if (a != null) SecondaryButton("Look at the latest photo again", vm::assessAndOutline, Modifier.fillMaxWidth(), enabled = s.busy == null, icon = Icons.Outlined.AutoAwesome)
        }
    }
    item { Knowledge(s) }
}

private fun LazyListScope.verificationItems(s: InspectionUi, vm: InspectionViewModel, view: InspectionView, onCapture: () -> Unit, importPhoto: () -> Unit, onOpenReceipt: (String) -> Unit, onGlasses: () -> Unit) {
    val accepted = view.photos.firstOrNull { it.evidenceId == view.acceptedPostRepairPhotoId }
    val change = view.changeAnalyses.lastOrNull { it.claimId == view.currentClaimId }
    val after = accepted ?: change?.let { c -> view.photos.firstOrNull { it.evidenceId == c.photoEvidenceId } }
    val claimedAt = view.repairClaimedAt?.let(::instant)
    val before = claimedAt?.let { at -> view.photos.lastOrNull { p -> instant(p.observedAt)?.let { it <= at } == true } } ?: view.photos.firstOrNull()
    val required = s.fixture?.visualRequirements?.filter { it.mandatory }.orEmpty()
    // The server's own answer for each item under this claim; the policy counts it whichever post-repair photo it cited.
    fun reviewed(req: String) = view.checklist.firstOrNull { it.requirementId == req }
    val running = s.busy?.startsWith("Server is verifying") == true
    // The server's next step picks the card; nothing here works out the order itself.
    val card = verifyCard(view)
    val attempt = if (running) null else s.attempt
    val blocking = view.readiness?.blocking.orEmpty()
    // The server lists every unreviewed item on its own; in plain words they read the same, so each line shows once.
    val waitingOn = blocking.map(::plainReason).distinct()
    val check = view.readiness?.checks?.firstOrNull()
    val target = s.fixture?.diagnostics?.firstOrNull()?.targetAlias
    val asked = view.evidenceRequests.firstOrNull { it.answeredByPhotoId == null }
    val reviewRows: @Composable () -> Unit = {
        ReviewRows(
            required.map { it.requirementId }, { reviewed(it)?.status },
            earlier = { req -> reviewed(req)?.reviewedByEvidenceId?.let { it != accepted?.evidenceId } == true },
            enabled = accepted != null && s.busy == null && (card == VerifyCard.Review || card == VerifyCard.Confirm),
        ) { req, st -> accepted?.let { vm.review(req, st, it.evidenceId) } }
    }
    val claimAgain: @Composable () -> Unit = {
        Field(s.claimText, vm::onClaimChanged, "What did you do this time?")
        PrimaryButton("Record the repair", { vm.claim(s.claimText) }, Modifier.fillMaxWidth(), enabled = s.busy == null && s.claimText.isNotBlank(), icon = Icons.Outlined.Refresh)
    }

    item(key = HERO_KEY) { VerifyHero(s, vm, before, after, camera = card != VerifyCard.Closed, onCapture) }
    if (attempt != null) attemptItems(s, vm, view, attempt, card, onCapture, onOpenReceipt, claimAgain)
    else item(key = NEXT_STEP_KEY) {
        when {
            running -> AiCard("Your photo, review and confirmation are in.", "Verifying…", "Measuring ${target ?: "the equipment"} now. The photo and the measurement both have to pass.")
            card == VerifyCard.Comparing -> AiCard("I'm comparing the new photo with the earlier view.", "Comparing the new photo…",
                "Verification waits for this. If it doesn't finish, take the photo again.") {
                SecondaryButton("Take it again", onCapture, Modifier.fillMaxWidth(), icon = Icons.Outlined.CameraAlt)
                SecondaryButton("Refresh", { vm.refresh() }, Modifier.fillMaxWidth(), enabled = s.busy == null, icon = Icons.Outlined.Refresh)
            }
            card == VerifyCard.Review -> AiCard(aiSummary(view, null), "Review the new photo", "Mark what you can see yourself. My outline is a suggestion.") {
                val answers = required.mapNotNull { reviewed(it.requirementId)?.status }
                when {
                    ChecklistStatus.NotSatisfied in answers -> Muted("“Not right” means the repair isn't finished. Fix it, then retake the photo.")
                    ChecklistStatus.Unknown in answers || ChecklistStatus.Conflicting in answers -> Muted("“Can't tell” holds verification. Retake a clearer photo, or change the answer if you can see it.")
                }
                reviewRows()
                SecondaryButton("Retake the photo", onCapture, Modifier.fillMaxWidth(), icon = Icons.Outlined.CameraAlt)
            }
            card == VerifyCard.Confirm -> AiCard("Your review is in.", "Confirm it's your review", "The receipt records that you checked this evidence yourself. A new photo or review needs confirming again.") {
                SecondaryButton("I reviewed this evidence myself", vm::acknowledge, Modifier.fillMaxWidth(), enabled = s.busy == null, icon = Icons.Outlined.HowToReg)
            }
            card == VerifyCard.Run -> AiCard("Photo accepted, reviewed and confirmed.", "Ready to verify",
                "Verify takes a fresh measurement of ${target ?: "the equipment"} now${if (check?.afterClaim == true && check.lastRunAt != null) "; the one from ${whenText(check.lastRunAt)} isn't reused" else ""}. Both proofs have to pass.") {
                PrimaryButton("Verify the repair", vm::verify, Modifier.fillMaxWidth(), enabled = s.busy == null, busy = running, icon = Icons.Outlined.Build)
            }
            card == VerifyCard.Claim -> AiCard("This repair attempt didn't pass.", "Fix it, then claim again", "Every attempt stays in the record.", tone = Fo.Failure) { claimAgain() }
            card == VerifyCard.Closed -> AiCard(plainReason(blocking.firstOrNull().orEmpty()), "Inspection closed", null)
            else -> AiCard(
                aiSummary(view, null), asked?.note ?: "Photograph the repair",
                asked?.let { "Requested by the ${it.requestedBy.lowercase()}. Your next photo answers it." } ?: "Same view as before. The same photo again won't count.",
            ) {
                PendingUploads(s)
                PrimaryButton("Open camera", onCapture, Modifier.fillMaxWidth(), icon = Icons.Outlined.CameraAlt)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Import", importPhoto, Modifier.weight(1f), icon = Icons.Outlined.Image)
                    SecondaryButton("Glasses", onGlasses, Modifier.weight(1f), icon = Icons.Outlined.Visibility)
                }
            }
        }
    }
    if (attempt == null && !running && waitingOn.size > 1 && card != VerifyCard.Closed) item {
        Expandable("Why verify can't run yet · ${waitingOn.size}") { waitingOn.forEach { Muted("· $it") } }
    }
    item { ProgressSegments(if (attempt?.decision?.result == VerificationResult.Verified) 4 else card.step, verifySteps) }
    if (attempt == null && accepted != null && (card == VerifyCard.Confirm || card == VerifyCard.Run)) item { Expandable("Your review · ${required.size} items") { reviewRows() } }
    if (s.attempts.isNotEmpty()) item {
        Muted("${s.attempts.size} attempts kept in the inspection record.")
    }
    if (view.stage != InspectionStage.VerifiedComplete) item { Expandable("Equipment check") { EquipmentCheck(s, vm, view) } }
}

/**
 * One decision, kept in three separate parts: what the photo showed (the server's change check), what the technician
 * confirmed, and what the equipment check measured for this attempt, then the decision with every reason.
 */
private fun LazyListScope.attemptItems(
    s: InspectionUi, vm: InspectionViewModel, view: InspectionView, a: Attempt, card: VerifyCard,
    onCapture: () -> Unit, onOpenReceipt: (String) -> Unit, claimAgain: @Composable () -> Unit,
) {
    val d = a.decision
    val verified = d.result == VerificationResult.Verified
    val simulated = d.outcomeKind == VerificationOutcomeKind.VerifiedCompleteSimulated || a.measured.any { it.source != DiagnosticSource.LiveLocal }
    val photoId = a.review.firstOrNull()?.photoEvidenceId ?: view.acceptedPostRepairPhotoId
    val change = photoId?.let { id -> view.changeAnalyses.lastOrNull { it.photoEvidenceId == id } }
    val tone = when (d.result) { VerificationResult.Verified -> Fo.Success; VerificationResult.NeedsHumanReview -> Fo.Warning; else -> Fo.Failure }
    val title = TechnicianText.outcome(d.result, d.outcomeKind)

    item(key = NEXT_STEP_KEY) {
        AiCard(
            if (verified) null else plainReason(d.reasons.firstOrNull().orEmpty()), title,
            when { verified -> "Your inspection record is ready."; card == VerifyCard.Photo -> "Take a new photo to try again. This attempt stays in the record."; else -> "This attempt stays in the record." },
            tone = tone,
        ) {
            if (simulated && a.measured.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    a.measured.map { it.source }.distinct().forEach { SourceBadge(it) }
                    Muted("This result does not verify live equipment.")
                }
            }
            if (s.receiptCheck?.isValid == false) Muted("The inspection record could not be verified. Review Details before relying on it.")
            if (verified) {
                PrimaryButton("View inspection record", { onOpenReceipt(vm.receiptUrl()) }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Receipt)

            }
            if (card == VerifyCard.Photo) PrimaryButton("Take a new photo", onCapture, Modifier.fillMaxWidth(), icon = Icons.Outlined.CameraAlt)
            if (card == VerifyCard.Claim) claimAgain()
        }
    }
    item {
        Expandable("Details · checks and inspection record") {
            Part(Icons.Outlined.Visibility, "What the photo showed", "Camera assessment · inferred",
                pass = when (change?.verdict) { null, VisualChangeVerdict.Unscorable -> null; VisualChangeVerdict.Changed -> true; else -> false }) {
                if (change != null) {
                    val c = changeText(change.verdict)
                    EvidenceBadge(c.state, c.label)
                    Muted(if (change.verdict == VisualChangeVerdict.Unscorable) "No visual change could be established; the visual part rests on your review." else changeText(change.verdict).detail)
                } else Muted("No accepted photo for this claim.")
            }

            Part(Icons.Outlined.HowToReg, "What you confirmed", "Your review",
                pass = if (a.review.isEmpty() || a.acknowledgedAt == null) false else if (a.review.all { it.status == ChecklistStatus.Satisfied }) true else null) {
                if (a.review.isEmpty()) Muted("Nothing reviewed for this claim.")
                a.review.forEach { r -> Muted("${featureName(r.requirementId)}: ${checklistLabel(r.status)}") }
                Muted(a.acknowledgedAt?.let { "Confirmed by you · ${whenText(it)}" } ?: "Not confirmed by you.")
            }

            Part(Icons.Outlined.Speed, "What was measured", "Equipment check · measured",
                pass = if (a.measured.isEmpty()) null else a.measured.all { it.passed == true }) {
                if (a.measured.isEmpty()) Muted("No check ran: the photo, review or confirmation decided first.")
                a.measured.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Connection check", fontWeight = FontWeight.Medium, fontSize = 13.sp); SourceBadge(m.source)
                    }
                    Muted("${TechnicianText.connection(m.passed)} · ${whenText(m.completedAt)}")
                    TechDetails(listOf("Measurement" to measuredValue(m)))
                }
            }

            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Decision", style = Eyebrow, color = Fo.Muted)
                    Text(TechnicianText.outcome(d.result, d.outcomeKind), color = tone, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                }
                d.reasons.map(::plainReason).distinct().forEach { Muted("· $it") }
                TechDetails(listOf("Policy" to d.policyVersion, "Reasons" to d.reasons.joinToString("\n")) + listOfNotNull(change?.let { "Change analysis" to it.reason }))
            }

            SecondaryButton("Verify record", vm::checkReceipt, Modifier.fillMaxWidth(), enabled = s.busy == null, icon = Icons.Outlined.Fingerprint)
            s.receiptCheck?.let { r ->
                Text(if (r.isValid) "Record verified" else "Record could not be verified. Do not rely on it until reviewed.", color = if (r.isValid) Fo.Success else Fo.Failure, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Where the photo and the next step's card sit in the list, so the screen can bring the card into view when the step changes. */
private const val HERO_KEY = "verify-hero"
private const val NEXT_STEP_KEY = "verify-next-step"

/**
 * When the step changes (a photo accepted, a review finished, a decision made), scroll just far enough to show the new
 * step's card: on a phone it can sit below the photo and look like the end of the round. Nothing moves on first show,
 * and a card already in view stays put so the photo being reviewed stays on screen.
 */
@Composable
private fun BringNextStepIntoView(list: LazyListState, step: Any?) {
    var shown by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(step) {
        val before = shown
        shown = step
        if (before == null || step == null || before == step) return@LaunchedEffect
        delay(150) // let the new card lay out first
        val info = list.layoutInfo
        val card = info.visibleItemsInfo.firstOrNull { it.key == NEXT_STEP_KEY }
        if (card == null) {
            info.visibleItemsInfo.firstOrNull { it.key == HERO_KEY }?.let { list.animateScrollToItem(it.index + 1) }
            return@LaunchedEffect
        }
        val hidden = card.offset + card.size - info.viewportEndOffset
        if (hidden > 0) list.animateScrollBy(minOf(hidden, card.offset - info.viewportStartOffset).toFloat())
    }
}

/** One of the three kinds of proof, labelled with where it came from. */
@Composable
private fun Part(icon: ImageVector, title: String, kind: String, pass: Boolean?, content: @Composable () -> Unit) {
    val color = when (pass) { true -> Fo.Success; false -> Fo.Failure; null -> Fo.Border }
    Panel(border = if (pass == null) Fo.Border else color.copy(alpha = 0.45f), background = if (pass == null) Fo.Surface else color.copy(alpha = 0.06f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, modifier = Modifier.size(18.dp)); Text(title, fontWeight = FontWeight.Medium)
        }
        Text(kind.uppercase(), style = Eyebrow, color = Fo.Muted)
        content()
    }
}

private fun instant(s: String): Instant? = runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()

/** Before and after on one frame. "After" is the repair photo with the server's change outline pinned to it. */
@Composable
private fun VerifyHero(s: InspectionUi, vm: InspectionViewModel, before: PhotoEvidence?, after: PhotoEvidence?, camera: Boolean, onCapture: () -> Unit) {
    val view = s.view ?: return
    var side by remember { mutableStateOf<Boolean?>(null) }
    val showAfter = side ?: (after != null)
    val photo = if (showAfter) after else before
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(FoDark.Overlay)) {
        val img = s.outlineImage
        when {
            photo == null -> Column(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clickable(onClick = onCapture), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.CameraAlt, null, tint = FoDark.Muted, modifier = Modifier.size(36.dp))
                Text("Tap to open the camera", color = FoDark.Muted, fontSize = 13.sp)
            }
            img != null && s.outlinePhotoId == photo.evidenceId -> {
                val change = view.changeAnalyses.lastOrNull { it.photoEvidenceId == photo.evidenceId }
                ArOverlay(img, s.outlines, arTags(s.fixture, if (showAfter) null else s.latestAssessment, s.outlines, change))
            }
            else -> vm.artifactFor(photo.evidenceId)?.let { AsyncImage(vm.artifactUrl(it), if (showAfter) "After" else "Before", Modifier.fillMaxWidth().aspectRatio(4f / 3f), contentScale = ContentScale.Fit) }
        }
        if (before != null) Row(Modifier.padding(12.dp).clip(RoundedCornerShape(50)).background(FoDark.Overlay.copy(alpha = 0.72f)).padding(3.dp)) {
            listOf(false to "Before", true to "After").forEach { (isAfter, label) ->
                val on = showAfter == isAfter
                val enabled = !isAfter || after != null
                Text(
                    label, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = if (on) Fo.OnPrimary else FoDark.Foreground.copy(alpha = if (enabled) 0.85f else 0.35f),
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Fo.Primary else Color.Transparent)
                        .clickable(enabled = enabled) { side = isAfter }.padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
        if (camera && photo != null) Box(
            Modifier.align(Alignment.BottomEnd).padding(12.dp).size(46.dp).clip(CircleShape).background(FoDark.Overlay.copy(alpha = 0.7f)).clickable(onClick = onCapture),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.CameraAlt, "New photo", tint = FoDark.Foreground) }
    }
}

/** One row per required item: your own call against the new photo. An answer given on an earlier photo of this claim says so. */
@Composable
private fun ReviewRows(requirements: List<String>, current: (String) -> ChecklistStatus?, earlier: (String) -> Boolean, enabled: Boolean, onPick: (String, ChecklistStatus) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        requirements.forEachIndexed { i, req ->
            if (i > 0) HorizontalDivider(color = Fo.Border)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(featureName(req), modifier = Modifier.weight(1f))
                if (earlier(req)) Muted("answered on an earlier photo")
            }
            val cur = current(req)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(ChecklistStatus.Satisfied, ChecklistStatus.NotSatisfied, ChecklistStatus.Unknown).forEach { st ->
                    val tone = when (st) { ChecklistStatus.Satisfied -> Fo.Success; ChecklistStatus.NotSatisfied -> Fo.Failure; else -> Fo.Warning }
                    val on = cur == st
                    Box(
                        Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (on) tone.copy(alpha = 0.15f) else Color.Transparent)
                            .border(1.dp, if (on) tone.copy(alpha = 0.5f) else Fo.Border, RoundedCornerShape(12.dp))
                            .clickable(enabled = enabled) { onPick(req, st) },
                        contentAlignment = Alignment.Center,
                    ) { Text(checklistLabel(st), color = if (on) tone else Fo.Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

@Composable
private fun Muted(text: String) = Text(TechnicianText.guidance(text), style = MaterialTheme.typography.bodySmall, color = Fo.Muted)

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
                Muted("${originLabel(p.origin)} · ${whenText(p.observedAt)}")
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
                if (rejected) "Not uploaded. ${com.lensqi.fieldops.ui.common.friendly(c.errorCode ?: "unknown", c.errorMessage.orEmpty())}" else "Awaiting upload · saved on this phone",
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
            true -> Triple(Fo.Success, Icons.Outlined.CheckCircle, "Connection check passed")
            false -> Triple(Fo.Failure, Icons.Outlined.HighlightOff, "Connection check failed")
            null -> Triple(Fo.Warning, Icons.Outlined.RadioButtonUnchecked, "Connection check unavailable")
        }
        Panel(border = color.copy(alpha = 0.4f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
                Text(text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                SourceBadge(r.source)
            }
            Muted("Checked ${whenText(r.completedAt)}")
            TechDetails(listOf("Measurement" to measuredValue(r), "HTTP status" to (r.httpStatus?.toString() ?: "Unavailable")))
            check?.claimVerified?.let { Muted("What a pass proves: $it") }
        }
    } else Muted("Run the approved check to confirm the fault.")
    check?.let { c ->
        SecondaryButton("Run approved check", { vm.runDiagnostic(c.diagnosticId) }, Modifier.fillMaxWidth(), enabled = s.busy == null, icon = Icons.Outlined.PlayArrow)
        Muted("Only checks the site approved for this equipment; the target is set by the site, not the app.")
        if (s.diagnosticsMode == DiagnosticSource.Simulated) {
            Row(Modifier.fillMaxWidth().border(1.dp, Fo.Border, RoundedCornerShape(10.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Demo: connection available", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Muted("Demo result only; this does not measure the equipment.")
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
        s.knowledgeCachedAt?.let { Muted("Copy saved on this phone at ${whenText(it)}") }
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
