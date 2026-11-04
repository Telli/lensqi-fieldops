package com.lensqi.fieldops.ui.inspection

import com.lensqi.fieldops.ui.common.TechnicianText

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.outlined.AutoAwesome
import com.lensqi.fieldops.ui.common.AiCard
import com.lensqi.fieldops.ui.common.MoveKind
import com.lensqi.fieldops.ui.common.ProgressSegments
import com.lensqi.fieldops.ui.common.aiSummary
import com.lensqi.fieldops.ui.common.rejectedAfterAccepted
import com.lensqi.fieldops.ui.common.CHANGE_CHECK_KIND
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
import com.lensqi.fieldops.domain.verifyPreflight
import com.lensqi.fieldops.domain.PreflightRow
import androidx.compose.material.icons.outlined.Speed
import com.lensqi.fieldops.ui.common.verifySteps
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
import com.lensqi.fieldops.data.queue.hasQueuedCommands
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
import com.lensqi.fieldops.ui.common.excludedReason
import com.lensqi.fieldops.ui.common.nextActionText
import com.lensqi.fieldops.ui.common.predictionOutcome
import com.lensqi.fieldops.ui.common.probeValue
import com.lensqi.fieldops.data.api.PredictionRow
import com.lensqi.fieldops.data.api.ReasonResponse
import androidx.compose.material3.AlertDialog
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
        // The review opens only once the photo is saved for upload; a photo that cannot be copied or used stays an error here.
        if (uri != null) scope.launch {
            val file = runCatching { copyToCache(context, uri) }.getOrNull()
            if (file == null) vm.importFailed() else if (vm.prepareReview(file, origin = "RecordedFixture")) onReview()
        }
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
            view?.let { EvidenceBadge(stageState(it.stage), stageLabel(it.stage, it.outcomeKind)) }
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
            s.notice?.let { item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.CloudOff, null, tint = Fo.Warning, modifier = Modifier.size(16.dp)); Text(it, color = Fo.Warning, style = MaterialTheme.typography.bodySmall)
            } } }
            // Saved photos and commands are listed in both modes, also before the server has any photo, so a refused one can be retried or removed.
            if (s.pending.any { it.state != CommandState.Uploaded }) item { PendingUploads(s, vm) }
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
            val set = latest?.let { s.outlineSets[it.evidenceId] }
            when {
                set != null -> ArOverlay(set.image, set.outlines, arTags(s.fixture, a, set.outlines), guide = target, sourceWidth = set.sourceWidth)
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
    // One AI card: what LensQi sees, the next move, the single action. A closed inspection shows how it closed instead.
    item {
        if (view.stage == InspectionStage.Inconclusive) AiCard(plainReason(s.stageReason ?: "The inspection is closed (Inconclusive)"), "Inspection closed · inconclusive",
            "Closing added no fault or repair record; earlier work stays on record, and the record keeps how it closed.")
        else AiCard(aiSummary(view, a), move?.title, move?.hint) {
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
                    ClaimField(s.claimText, vm::onClaimChanged, "What did you do?")
                    PrimaryButton("Repair done — verify", { vm.claim(s.claimText) }, Modifier.fillMaxWidth(), enabled = s.busy == null && s.claimText.isNotBlank(), icon = Icons.Outlined.Build)
                }
                else -> {}
            }
            if (view.stage == InspectionStage.NeedsEvidence && view.awaitingRequestedView) DeclineView(s, vm)
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
    item { GroundedAdvisor(s, vm, view, onCapture) }
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
    // A later photo the server rejected is shown as "After · not accepted": the earlier accepted one is no longer the news.
    val rejectedLater = rejectedAfterAccepted(view)
    val after = rejectedLater?.let { c -> view.photos.firstOrNull { it.evidenceId == c.photoEvidenceId } }
        ?: accepted ?: change?.let { c -> view.photos.firstOrNull { it.evidenceId == c.photoEvidenceId } }
    val before = beforePhoto(view)
    val required = s.fixture?.visualRequirements?.filter { it.mandatory }.orEmpty()
    // The server's own answer for each item under this claim; the policy counts it whichever post-repair photo it cited.
    fun reviewed(req: String) = view.checklist.firstOrNull { it.requirementId == req }
    val running = s.busy?.startsWith("Server is verifying") == true
    // Answers saved on the phone have not reached the server: its review is not yet the technician's.
    val answersWaiting = s.pending.hasQueuedCommands()
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
        ClaimField(s.claimText, vm::onClaimChanged, "What did you do this time?")
        PrimaryButton("Record the repair", { vm.claim(s.claimText) }, Modifier.fillMaxWidth(), enabled = s.busy == null && s.claimText.isNotBlank(), icon = Icons.Outlined.Refresh)
    }

    item(key = HERO_KEY) { VerifyHero(s, vm, before, after, afterAccepted = rejectedLater == null, camera = card != VerifyCard.Closed, onCapture) }
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
                if (answersWaiting) Muted("Your review answers are still waiting to be sent; confirm once they have gone through.")
                SecondaryButton("I reviewed this evidence myself", vm::acknowledge, Modifier.fillMaxWidth(), enabled = s.busy == null && !answersWaiting, icon = Icons.Outlined.HowToReg)
            }
            card == VerifyCard.Run -> AiCard("Photo accepted, reviewed and confirmed.", "Ready to verify",
                "Verify takes a fresh measurement of ${target ?: "the equipment"} now${if (check?.afterClaim == true && check.lastRunAt != null) "; the one from ${whenText(check.lastRunAt)} isn't reused" else ""}. Both proofs have to pass.") {
                if (answersWaiting) Muted("Your review answers are still waiting to be sent; verify once they have gone through.")
                PrimaryButton("Verify the repair", vm::verify, Modifier.fillMaxWidth(), enabled = s.busy == null && !answersWaiting, busy = running, icon = Icons.Outlined.Build)
            }
            card == VerifyCard.Claim -> AiCard("This repair attempt didn't pass.", "Fix it, then claim again", "Every attempt stays in the record.", tone = Fo.Failure) { claimAgain() }
            card == VerifyCard.Closed -> AiCard(plainReason(blocking.firstOrNull().orEmpty()), "Inspection closed", null) {
                // Offline the attempt trail is not loaded, so this card is all that says how it closed: a simulated check is never left unbadged.
                if (view.outcomeKind == VerificationOutcomeKind.VerifiedCompleteSimulated) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SimBadge(); Muted("This result does not verify live equipment.")
                }
            }
            else -> AiCard(
                aiSummary(view, null), asked?.note ?: "Photograph the repair",
                asked?.let { "Requested by the ${it.requestedBy.lowercase()}. Your next photo answers it." }
                    ?: if (rejectedLater != null) "The last photo was not accepted. Take a new one after the repair; the same view as before won't count."
                    else "Same view as before. The same photo again won't count.",
            ) {
                // Saved photos are listed once, above the cards, in both modes.
                // The server refuses a repair photo whose device time is far behind the claim: say so before the shutter.
                val skew by com.lensqi.fieldops.data.api.ClockSkew.offsetMs.collectAsStateWithLifecycle()
                com.lensqi.fieldops.data.api.ClockSkew.warning(skew)?.let { Text(it, color = Fo.Warning, style = MaterialTheme.typography.bodySmall) }
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
    // What verify will need, from the server's view, so a failed attempt is rarer and never a surprise.
    if (attempt == null && !running && (card == VerifyCard.Review || card == VerifyCard.Confirm || card == VerifyCard.Run)) item {
        Expandable("Before you verify · the three proofs") {
            verifyPreflight(view, required.map { it.requirementId }).forEach { row -> PreflightLine(row) }
            Muted("Verify measures the equipment again and decides on the server. This list only shows where things stand.")
        }
    }
    item { ProgressSegments(if (attempt?.decision?.result == VerificationResult.Verified) 4 else card.step, verifySteps) }
    item { PredictionPanel(s, vm, view) }
    if (attempt == null && accepted != null && (card == VerifyCard.Confirm || card == VerifyCard.Run)) item { Expandable("Your review · ${required.size} items") { reviewRows() } }
    if (s.history.isNotEmpty()) item {
        Expandable("Attempts · ${s.history.size} kept in the inspection record") {
            s.history.asReversed().forEachIndexed { i, a ->
                if (i > 0) HorizontalDivider(color = Fo.Border)
                val d = a.decision
                val tone = when (d.result) { VerificationResult.Verified -> Fo.Success; VerificationResult.NeedsHumanReview -> Fo.Warning; else -> Fo.Failure }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Attempt ${s.history.size - i}", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(TechnicianText.outcome(d.result, d.outcomeKind), color = tone, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Muted(whenText(a.at))
                if (!d.isVerified) d.reasons.firstOrNull()?.let { Muted(plainReason(it)) }
                // Which proof decided it: the equipment check that ran, or the photo, review or confirmation before any check.
                Muted(when {
                    a.measured.any { it.passed == false } -> "Equipment check failed · ${a.measured.map { it.source }.distinct().joinToString { it.name.lowercase() }}"
                    a.measured.isNotEmpty() -> "Equipment check ${if (a.measured.all { it.passed == true }) "passed" else "unavailable"} · ${a.measured.map { it.source }.distinct().joinToString { it.name.lowercase() }}"
                    a.acknowledgedAt == null -> "Decided before the check: confirmation was missing"
                    a.review.isEmpty() -> "Decided before the check: nothing was reviewed"
                    a.review.any { it.status != ChecklistStatus.Satisfied } -> "Decided before the check: a review item was not right or undecided"
                    else -> "Decided before the check ran"
                })
            }
        }
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
            Part(Icons.Outlined.Visibility, "What the photo showed", CHANGE_CHECK_KIND,
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
                // Failed only when a check failed; a check with no measurement is unavailable, not a failure.
                pass = when { a.measured.any { it.passed == false } -> false; a.measured.isEmpty() || a.measured.any { it.passed == null } -> null; else -> true }) {
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

/** One line of the preflight: the proof, where it stands, and why. */
@Composable
private fun PreflightLine(row: PreflightRow) {
    val (color, icon) = when (row.ok) { true -> Fo.Success to Icons.Outlined.CheckCircle; false -> Fo.Failure to Icons.Outlined.HighlightOff; null -> Fo.Muted to Icons.Outlined.RadioButtonUnchecked }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.padding(top = 2.dp).size(16.dp))
        Column { Text(row.title, fontWeight = FontWeight.Medium, fontSize = 13.sp); Muted(row.detail) }
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

/** Before and after on one frame. "After" is the repair photo with the server's change outline pinned to it. */
@Composable
private fun VerifyHero(s: InspectionUi, vm: InspectionViewModel, before: PhotoEvidence?, after: PhotoEvidence?, afterAccepted: Boolean, camera: Boolean, onCapture: () -> Unit) {
    val view = s.view ?: return
    var side by remember { mutableStateOf<Boolean?>(null) }
    val showAfter = side ?: (after != null)
    val photo = if (showAfter) after else before
    val afterLabel = if (afterAccepted) "After" else "After · not accepted"
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(FoDark.Overlay)) {
        // Each photo's outlines are cached by id, so toggling Before keeps them.
        val set = photo?.let { s.outlineSets[it.evidenceId] }
        when {
            photo == null -> Column(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clickable(onClick = onCapture), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.CameraAlt, null, tint = FoDark.Muted, modifier = Modifier.size(36.dp))
                Text("Tap to open the camera", color = FoDark.Muted, fontSize = 13.sp)
            }
            set != null -> {
                val change = view.changeAnalyses.lastOrNull { it.photoEvidenceId == photo.evidenceId }
                ArOverlay(set.image, set.outlines, arTags(s.fixture, if (showAfter) null else s.latestAssessment, set.outlines, change), sourceWidth = set.sourceWidth)
            }
            else -> vm.artifactFor(photo.evidenceId)?.let { AsyncImage(vm.artifactUrl(it), if (showAfter) afterLabel else "Before", Modifier.fillMaxWidth().aspectRatio(4f / 3f), contentScale = ContentScale.Fit) }
        }
        if (before != null) Row(Modifier.padding(12.dp).clip(RoundedCornerShape(50)).background(FoDark.Overlay.copy(alpha = 0.72f)).padding(3.dp)) {
            listOf(false to "Before", true to afterLabel).forEach { (isAfter, label) ->
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
                // What the phone measured at the shutter, labelled as measured; the still itself has no depth.
                p.measured?.takeIf { it.isNotEmpty() }?.let { m -> Muted("Measured · " + m.joinToString("; ") { part -> part.part + listOfNotNull(part.distanceCm?.let { " ${it} cm away" }, part.heightAboveFloorCm?.let { " ${it} cm up" }).joinToString(",") }) }
            }
        }
    }
}

@Composable
private fun PendingUploads(s: InspectionUi, vm: InspectionViewModel) {
    s.pending.filter { it.state != CommandState.Uploaded }.forEach { c ->
        val rejected = c.state == CommandState.Rejected
        val what = when (c.kind) {
            com.lensqi.fieldops.data.queue.CommandKind.RepairClaim -> "repair claim"
            com.lensqi.fieldops.data.queue.CommandKind.Checklist -> "review answer"
            com.lensqi.fieldops.data.queue.CommandKind.Acknowledge -> "confirmation"
            com.lensqi.fieldops.data.queue.CommandKind.WorkOrder -> "work order"
            else -> "photo"
        }
        if (rejected) androidx.compose.material3.TextButton(onClick = { vm.retryUpload(c) }) {
            Text(if (c.serverBase == null) "Retry saved $what on this server" else "Retry saved $what")
        }
        if (c.state == CommandState.Rejected) TextButton(onClick = { vm.removeUpload(c) }) { Text("Remove saved $what") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Dot(if (rejected) Fo.Failure else Fo.Warning)
            Text(
                if (rejected) "${what.replaceFirstChar { it.uppercase() }} not sent. ${com.lensqi.fieldops.ui.common.friendly(c.errorCode ?: "unknown", c.errorMessage.orEmpty())}"
                else "${what.replaceFirstChar { it.uppercase() }} saved on this phone · sent when connected",
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
                    Text(if (s.simUp == false) "Demo: connection down" else "Demo: connection available", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Muted("Demo result only; this does not measure the equipment.")
                }
                Switch(s.simUp == true, { vm.setSimulated(c.diagnosticId, it) }, enabled = s.busy == null, colors = SwitchDefaults.colors(checkedTrackColor = Fo.Primary, checkedThumbColor = Fo.OnPrimary))
            }
        }
    }
}

@Composable
private fun Knowledge(s: InspectionUi) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val k = s.knowledge ?: return
    Expandable("Vendor knowledge") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (s.knowledgeCachedAt != null) Tag("CACHED", Fo.Warning)
            Muted(if (k.packId != null) "${k.facts.size} sourced facts" else "No documentation pack for this equipment")
        }
        s.knowledgeCachedAt?.let { Muted("Copy saved on this phone at ${whenText(it)}") }
        k.packRevision?.let { Muted("Revision $it") }
        k.packSha256?.let { Muted("Pack digest: $it") }
        k.originKind?.let { Tag(if (it == "Synthetic") "SYNTHETIC" else "VENDOR LINKED", Fo.Warning) }
        k.warnings.forEach { Text(it, color = Fo.Warning, fontSize = 12.sp) }
        Muted("Guidance with sources. Documentation never proves a repair worked.")
        k.facts.forEach { f ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Dot(Fo.Primary)
                    Text(if (f.provenance == "SiteConfigured") "Site setup" else "Vendor documentation", style = Eyebrow)
                    if (s.knowledgeCachedAt != null) Tag("CACHED", Fo.Warning)
                    if (k.originKind == "Synthetic") Tag("SYNTHETIC", Fo.Warning)
                }
                Text(f.statement, fontSize = 13.sp)
                // The source sheet: document, section, publisher, when it was read, the pack revision, and what kind of claim it is.
                Expandable("Source · ${f.citation.documentTitle}") {
                    Muted("Section: ${f.citation.sourceLocator}")
                    Muted("Publisher: ${f.citation.publisher} · read ${f.citation.accessedOn}")
                    Muted("Pack revision ${k.packRevision ?: "—"}" + (k.packSha256?.let { " · digest ${it.take(12)}…" } ?: ""))
                    Muted("${f.claimKind.replace(Regex("([a-z])([A-Z])"), "$1 $2")} · safety: ${f.safety.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()}" + if (f.tags.isNotEmpty()) " · ${f.tags.joinToString(", ")}" else "")
                    Muted(if (s.knowledgeCachedAt != null) "Copy saved on this phone at ${whenText(s.knowledgeCachedAt)}; it may be out of date." else "Fetched from the server now.")
                    f.citation.sourceUrl?.takeIf { com.lensqi.fieldops.ui.receipt.webOrigin(it) != null }?.let { url ->
                        androidx.compose.material3.TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text("Open source") }
                    }
                }
            }
        }
        // Facts the server kept away from this unit, each with its reason: the pack may describe other firmware or hardware.
        if (k.excluded.isNotEmpty()) Expandable("Not applied to this unit · ${k.excluded.size}") {
            Muted("The server did not apply these to this equipment. They are never used for advice or predictions here.")
            k.excluded.forEach { x ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Dot(Fo.Warning)
                    Text(x.factId, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Tag(x.verdict.uppercase(), Fo.Muted)
                }
                Muted(excludedReason(x.reason))
            }
            TechDetails(k.excluded.map { it.factId to it.reason })
        }
    }
}

/**
 * Grounded advice from the unit's documentation. The server resolves the facts, asks the model, validates the proposal and
 * records it, accepted or refused; the phone shows the hypothesis as written and the facts it rests on with their sources.
 * Advice is inferred: a suggestion, never evidence, and never a stage.
 */
@Composable
private fun GroundedAdvisor(s: InspectionUi, vm: InspectionViewModel, view: InspectionView, onCapture: () -> Unit) {
    val k = s.knowledge
    val closed = view.stage == InspectionStage.VerifiedComplete || view.stage == InspectionStage.Inconclusive
    Expandable("Grounded advisor") {
        Muted("Advice grounded only in this unit's documentation. The server checks every suggestion against the sources and records it, accepted or refused.")
        when {
            k == null -> Muted(if (s.viewCachedAt != null) "Documentation isn't available offline for this inspection." else "Documentation hasn't loaded yet.")
            k.packId == null -> Muted("No documentation pack for this equipment, so there is nothing to ground advice in")
            else -> {
                // Starts blank: what is sent is recorded as the technician's observation, so LensQi's own reading of the photo
                // is never sent back as theirs, and the advisor is never grounded on the model's earlier words.
                val draft = s.observationText.orEmpty()
                Field(draft, vm::onObservationChanged, "What you observe, in your own words")
                PrimaryButton("Ask grounded advisor", { vm.askAdvisor(draft) }, Modifier.fillMaxWidth(),
                    enabled = s.busy == null && !closed && draft.isNotBlank(), busy = s.busy?.startsWith("Asking the grounded advisor") == true, icon = Icons.Outlined.AutoAwesome)
                if (closed) Muted("The inspection is closed; the advisor takes no more questions.")
                if (s.knowledgeCachedAt != null) Muted("The documentation shown is the copy saved on this phone; asking needs a connection.")
            }
        }
        s.advice?.let { AdvisorAnswer(it, s, vm, onCapture) }
    }
}


@Composable
private fun AdvisorAnswer(r: ReasonResponse, s: InspectionUi, vm: InspectionViewModel, onCapture: () -> Unit) {
    val again = if (r.replayed) " (same answer as before)" else ""
    val p = r.proposal
    if (r.accepted && p != null) {
        val facts = s.knowledge?.facts.orEmpty()
        AiCard(p.hypothesis, "Inferred · AI suggestion, not evidence$again", nextActionText(p.nextAction), verbatim = true) {
            if (p.usedFactIds.isNotEmpty()) {
                Text("Based on", style = Eyebrow, color = Fo.Muted)
                p.usedFactIds.forEach { id ->
                    val f = facts.firstOrNull { it.factId == id }
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Dot(Fo.Primary, 5)
                        Column { Text(f?.statement ?: id, fontSize = 13.sp); Muted(f?.citation?.documentTitle ?: "Fact $id") }
                    }
                }
            }
            p.requestedVisualEvidence?.takeIf { it.isNotBlank() }?.let { asked ->
                Text("LensQi asks for this view: $asked", fontSize = 13.sp)
                SecondaryButton("Open camera", onCapture, Modifier.fillMaxWidth(), icon = Icons.Outlined.CameraAlt)
            }
            r.probePrediction?.let { prediction ->
                val id = prediction.diagnosticId
                val approved = s.fixture?.diagnostics?.any { it.diagnosticId == id } == true
                Text("Proposed check: $id", fontSize = 13.sp)
                if (approved) SecondaryButton("Run the check", { vm.runDiagnostic(id) }, Modifier.fillMaxWidth(), enabled = s.busy == null, icon = Icons.Outlined.PlayArrow)
                else Muted("This check isn't on the site's approved list for the equipment, so it can't run from here.")
            }
            r.probePrediction?.predictedValue?.let { v ->
                Muted("LensQi predicts the check will report ${probeValue(v)} · recorded before the check runs")
            }
            Muted("A suggestion from the documentation, not evidence. Your review and the equipment check decide.")
            TechDetails(listOf("Proposal" to r.proposalId, "Model" to r.modelId, "Next action" to p.nextAction.name) + listOfNotNull(r.prediction?.let { "Prediction" to it.predictionId }))
        }
    } else {
        Panel(border = Fo.Warning.copy(alpha = 0.45f), background = Fo.Warning.copy(alpha = 0.06f)) {
            Text("The server refused LensQi's suggestion and recorded why$again", fontWeight = FontWeight.Medium, color = Fo.Warning)
            r.rejections.forEach { x -> Muted("${x.code} · ${x.message}") }
            if (r.rejections.isEmpty()) Muted("No reason was given.")
            Muted("Nothing it asked for was run.")
        }
    }
}

/** The technician cannot safely take the view LensQi asked for; the server closes the inspection as inconclusive and records the decline. */
@Composable
private fun DeclineView(s: InspectionUi, vm: InspectionViewModel) {
    var confirm by remember { mutableStateOf(false) }
    TextButton(onClick = { confirm = true }, enabled = s.busy == null) { Text("I can't safely get this view", color = Fo.Muted) }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Close this inspection as inconclusive?") },
        text = { Text("LensQi asked for a view you can't safely capture. The inspection closes as inconclusive: this adds no fault or repair record, earlier work stays on record, and the record keeps that the view was declined.") },
        confirmButton = { TextButton(onClick = { confirm = false; vm.declineView() }) { Text("Close as inconclusive") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep going") } },
    )
}

private fun instant(iso: String?): java.time.Instant? = iso?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull() }

/**
 * The model says what the equipment check will report before it runs (inferred); the check then measures it and the server
 * scores the prediction. Calibration only: the measurement decides verification, never the prediction.
 */
@Composable
private fun PredictionPanel(s: InspectionUi, vm: InspectionViewModel, view: InspectionView) {
    val since = instant(view.repairClaimedAt)
    val registered: PredictionRow? = s.predictions?.predictions?.filter { p -> since != null && instant(p.registeredAt)?.let { !it.isBefore(since) } == true }?.maxByOrNull { it.registeredAt }
    val canAsk = view.currentClaimId != null && view.stage != InspectionStage.VerifiedComplete && view.stage != InspectionStage.Inconclusive
    if (registered == null && !canAsk) return
    val (state, label) = predictionOutcome(registered?.outcome)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = Fo.Primary, modifier = Modifier.size(14.dp))
                Text("Prediction before the check", style = Eyebrow, color = Fo.Muted)
            }
            if (registered != null) EvidenceBadge(state, label)
        }
        if (s.predictionsCachedAt != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag("CACHED", Fo.Warning); Muted("Copy saved on this phone at ${whenText(s.predictionsCachedAt)}")
        }
        if (registered != null) {
            val scored = registered.outcome != "Pending"
            val measuredTone = when (registered.outcome) { "Confirmed" -> Fo.Success; "Falsified" -> Fo.Failure; else -> Fo.Border }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).background(Fo.Primary.copy(alpha = 0.05f), RoundedCornerShape(12.dp)).border(1.dp, Fo.Primary.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(10.dp)) {
                    Muted("LensQi predicted · inferred")
                    Text(probeValue(registered.predictedValue), fontWeight = FontWeight.Medium)
                }
                Column(Modifier.weight(1f).background(if (scored) measuredTone.copy(alpha = 0.06f) else Color.Transparent, RoundedCornerShape(12.dp))
                    .border(1.dp, if (scored) measuredTone.copy(alpha = 0.4f) else Fo.Border, RoundedCornerShape(12.dp)).padding(10.dp)) {
                    Muted("Check result")
                    if (scored) {
                        val source = registered.sourceIn(view)
                        if (source != null) SourceBadge(source) else Muted("Source unavailable")
                    }
                    Text(if (scored) probeValue(registered.measuredValue) else "not run yet", fontWeight = FontWeight.Medium)
                }
            }
            registered.scoredAt?.let { Muted("Scored by the server · ${whenText(it)}") }
        } else {
            val refused = s.refusedCodes
            Muted(if (refused != null) "The server refused LensQi's last suggestion (${refused.joinToString(", ")}). It ran nothing and the refusal is in the record."
                else "Ask LensQi to say what the equipment check will report, before it runs. It's recorded first, then scored against the measurement.")
            val claim = s.claimDescription ?: s.claimText
            SecondaryButton("Ask LensQi to predict the check", { vm.askAdvisor("Technician's repair claim: $claim. Predict what the approved equipment check will report.") },
                Modifier.fillMaxWidth(), enabled = s.busy == null && s.viewCachedAt == null, icon = Icons.Outlined.AutoAwesome)
        }
        Muted("A prediction is model calibration. The measurement decides verification, never the prediction.")
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

/** Dictation edits only the draft: the person reviews it before pressing the separate record/verify button. */
@Composable
private fun ClaimField(text: String, onChange: (String) -> Unit, label: String) {
    val voice = com.lensqi.fieldops.voice.rememberVoiceInput(onFinal = onChange)
    Field(text, onChange, label)
    if (voice.supported) {
        TextButton(onClick = { if (voice.listening) voice.stop() else voice.start() }) {
            Text(if (voice.listening) "Finish dictating" else "Dictate repair claim")
        }
        if (voice.listening) Text(voice.heard.ifBlank { "Listening…" })
        Muted("Review the text before recording the repair. Voice uses your phone’s speech service.")
        voice.error?.let { Text(it, color = Fo.Warning) }
    }
}
