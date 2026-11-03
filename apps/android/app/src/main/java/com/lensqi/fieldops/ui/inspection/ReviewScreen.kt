package com.lensqi.fieldops.ui.inspection

import com.lensqi.fieldops.ui.common.TechnicianText

import android.graphics.Bitmap
import androidx.compose.runtime.produceState
import com.lensqi.fieldops.ui.camera.uprightBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lensqi.fieldops.ui.common.AssetMatch
import com.lensqi.fieldops.ui.common.LensQiMark
import com.lensqi.fieldops.ui.common.aiSummary
import com.lensqi.fieldops.ui.common.nextMove
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lensqi.fieldops.data.api.ObservationStatus
import com.lensqi.fieldops.data.api.ProposedNextAction
import com.lensqi.fieldops.data.api.VisualChangeVerdict
import com.lensqi.fieldops.ui.common.EvidenceBadge
import com.lensqi.fieldops.ui.common.PrimaryButton
import com.lensqi.fieldops.ui.common.SecondaryButton
import com.lensqi.fieldops.ui.common.changeText
import com.lensqi.fieldops.ui.common.featureName
import com.lensqi.fieldops.data.api.EvidenceOrigin
import com.lensqi.fieldops.ui.common.isPostRepairItem
import com.lensqi.fieldops.ui.common.observationLabel
import com.lensqi.fieldops.ui.common.sentence
import com.lensqi.fieldops.ui.theme.FoDark
import com.lensqi.fieldops.ui.theme.ForceDark

/**
 * The frozen frame after the shutter, as in the design's camera: progress while the server works, then the photo with
 * the AI's outlines and pinned labels, the asset match, and what to do next. Every result comes from the API.
 */
@Composable
fun ReviewScreen(vm: InspectionViewModel, onContinue: () -> Unit, onRetake: () -> Unit) = ForceDark { ReviewContent(vm, onContinue, onRetake) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewContent(vm: InspectionViewModel, onContinue: () -> Unit, onRetake: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val r = s.review
    if (r == null) {
        // No review to show (the photo could not be saved or read): say why, with a way back, instead of a blank screen.
        Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Nothing to review", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = FoDark.Foreground)
            Text(s.error ?: "The photo could not be prepared for review.", fontSize = 13.sp, color = FoDark.Muted)
            SecondaryButton("Close", onContinue, Modifier.fillMaxWidth())
        }
        return
    }
    val view = s.view
    // Decoded off the main thread, upright (EXIF applied) and bounded, so a portrait capture is not shown sideways while the outlines load.
    val local by produceState<Bitmap?>(null, r.file) { value = withContext(Dispatchers.IO) { runCatching { uprightBitmap(r.file.readBytes(), 1600) }.getOrNull() } }
    val set = r.photoId?.let { s.outlineSets[it] }
    val outlinesReady = r.done && set != null
    val outlines = set?.outlines.orEmpty()
    val change = view?.changeAnalyses?.lastOrNull { it.photoEvidenceId == r.photoId }
    val assessment = if (r.afterRepair) null else assessmentForPhoto(s.latestAssessment, r.photoId)
    val tags = if (outlinesReady) arTags(s.fixture, assessment, outlines, change) else emptyList()
    val target = if (outlinesReady) targetConcept(s.fixture, assessment) else null
    val rejected = r.refused != null || change?.verdict == VisualChangeVerdict.DuplicateImage || change?.verdict == VisualChangeVerdict.NoVisualChange
    val expected = s.fixture?.assets?.firstOrNull { it.assetId == view?.assetId }?.displayName ?: view?.assetId.orEmpty()
    val glass = Color(0xCC0B0F14)

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { vm.closeReview(); onContinue() }, modifier = Modifier.clip(CircleShape).background(glass)) { Icon(Icons.Outlined.Close, "Close", tint = FoDark.Foreground) }
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(glass).padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val (dot, text) = when {
                        r.afterRepair -> null to "$expected · post-repair photo"
                        r.assetMatch == null && !r.done -> null to "Matching asset…"
                        r.assetMatch == AssetMatch.Mismatch -> FoDark.Warning to "Looks like ${r.assetSeen}, not $expected"
                        r.assetMatch == AssetMatch.Match -> FoDark.Success to "$expected · matches site record"
                        r.assetMatch == AssetMatch.Unseen -> FoDark.Warning to "Can't confirm $expected in this photo"
                        else -> null to "$expected · from asset label"
                    }
                    dot?.let { Box(Modifier.size(7.dp).background(it, CircleShape)) }
                    Text(text, fontSize = 12.sp, maxLines = 1, color = if (dot == FoDark.Warning) FoDark.Warning else FoDark.Foreground)
                }
            }
            // Where the photo came from, always visible: mock glasses are never presented as real glasses.
            runCatching { EvidenceOrigin.valueOf(r.origin) }.getOrNull()?.takeIf { it != EvidenceOrigin.Phone }?.let { o ->
                Text(
                    when (o) {
                        EvidenceOrigin.MockWearables -> "Photo from mock glasses · Meta Mock Device Kit, simulated"
                        EvidenceOrigin.Glasses -> "Photo from the glasses"
                        else -> "Imported photo"
                    },
                    fontSize = 12.sp, color = if (o == EvidenceOrigin.MockWearables) FoDark.Warning else FoDark.Muted,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                )
            }
            Box(Modifier.fillMaxWidth()) {
                if (set != null && outlinesReady) ArOverlay(set.image, outlines, tags, guide = target, guideLabel = "Target region", sourceWidth = set.sourceWidth)
                else local?.let { Image(it.asImageBitmap(), "Captured photo", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
                if (!r.done && !r.awaitingUpload) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = FoDark.Primary, trackColor = Color.Transparent)
            }
        }

        Column(
            Modifier.padding(12.dp).fillMaxWidth()
                .clip(RoundedCornerShape(26.dp)).background(glass).border(1.dp, FoDark.Border, RoundedCornerShape(26.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // LensQi's read of the photo and the next move, from the server's assessment and change check.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LensQiMark()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (r.done && !r.awaitingUpload && r.refused == null) Text(aiSummary(view, assessment), fontSize = 13.sp, lineHeight = 19.sp, color = FoDark.Muted)
                    Text(
                        when {
                            r.awaitingUpload -> "Saved on this phone"
                            !r.done -> if (r.afterRepair) "Comparing with the photo from before…" else "Looking at your photo…"
                            r.refused != null -> "Photo not uploaded"
                            rejected -> "Photo not accepted"
                            r.afterRepair -> "Photo accepted"
                            else -> nextMove(view, assessment)?.title ?: "Here's what I see"
                        },
                        fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, color = if (rejected) FoDark.Failure else FoDark.Foreground,
                    )
                    if (!r.done || r.steps.any { it.state == StepState.Skipped }) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        r.steps.forEach { st ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                when (st.state) {
                                    StepState.Done -> Icon(Icons.Outlined.Check, null, tint = FoDark.Primary, modifier = Modifier.size(13.dp))
                                    StepState.Doing -> CircularProgressIndicator(Modifier.size(11.dp), color = FoDark.Foreground, strokeWidth = 1.5.dp)
                                    StepState.Skipped -> Icon(Icons.Outlined.WarningAmber, null, tint = FoDark.Warning, modifier = Modifier.size(13.dp))
                                    StepState.Todo -> {}
                                }
                                Text(st.label, fontSize = 11.sp, color = when (st.state) { StepState.Done -> FoDark.Primary; StepState.Doing -> FoDark.Foreground; else -> FoDark.Muted })
                            }
                        }
                    }
                }
            }
            r.refused?.let { Text("$it The photo stays saved on this phone; retry or remove it from the inspection.", fontSize = 12.sp, color = FoDark.Failure) }
            if (r.awaitingUpload) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudOff, null, tint = FoDark.Warning, modifier = Modifier.size(16.dp))
                Text("No connection to the inspection server. The original photo is queued and uploads automatically; LensQi looks at it once it's there.", fontSize = 12.sp, color = FoDark.Warning)
            }
            val findings = assessment?.hypotheses?.filterNot { isPostRepairItem(it.featureId) }.orEmpty()
            if (r.done && (findings.isNotEmpty() || change != null || (!r.afterRepair && outlines.isEmpty()))) {
                var open by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (open) "Hide details" else if (tags.isNotEmpty()) "Details · tap a label on the photo for the reason" else "Details", fontSize = 12.sp, color = FoDark.Muted, modifier = Modifier.weight(1f))
                    Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = FoDark.Muted, modifier = Modifier.size(18.dp))
                }
                if (open) {
                    change?.let { c ->
                        val t = changeText(c.verdict)
                        EvidenceBadge(t.state, t.label)
                        Text(changeText(c.verdict).detail, fontSize = 12.sp, color = FoDark.Muted)
                    }
                    findings.forEach { h ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.padding(top = 5.dp).size(6.dp).background(when (h.status) { ObservationStatus.Observed -> FoDark.Primary; ObservationStatus.Conflicting -> FoDark.Failure; else -> FoDark.Warning }, CircleShape))
                            Column {
                                Text("${featureName(h.featureId)} · ${observationLabel(h.status).second}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text(TechnicianText.guidance(h.reason), fontSize = 12.sp, color = FoDark.Muted)
                            }
                        }
                    }
                    if (!r.afterRepair && outlines.isEmpty()) Text("No part could be outlined in this photo. Nothing was guessed.", fontSize = 12.sp, color = FoDark.Muted)
                }
            }
            if (rejected || target != null) {
                PrimaryButton(if (target != null) "Capture the requested view" else "Take another photo", { vm.closeReview(); onRetake() }, Modifier.fillMaxWidth(), icon = Icons.Outlined.CameraAlt)
                if (!rejected) SecondaryButton("Continue", { vm.closeReview(); onContinue() }, Modifier.fillMaxWidth())
            } else if (r.done || r.awaitingUpload) {
                PrimaryButton(if (r.afterRepair) "Continue to verification" else "Continue", { vm.closeReview(); onContinue() }, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Outlined.ArrowForward)
                SecondaryButton("Take another photo", { vm.closeReview(); onRetake() }, Modifier.fillMaxWidth(), icon = Icons.Outlined.CameraAlt)
            } else SecondaryButton("Cancel", { vm.cancel(); vm.closeReview(); onContinue() }, Modifier.fillMaxWidth())
        }
    }
}
