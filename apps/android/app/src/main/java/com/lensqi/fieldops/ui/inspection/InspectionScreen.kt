package com.lensqi.fieldops.ui.inspection

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lensqi.fieldops.data.api.ChecklistStatus
import com.lensqi.fieldops.data.api.DiagnosticSource
import com.lensqi.fieldops.data.api.InspectionStage
import com.lensqi.fieldops.data.api.ObservationStatus
import com.lensqi.fieldops.data.api.VerificationOutcomeKind
import com.lensqi.fieldops.data.api.VerificationResult
import com.lensqi.fieldops.data.api.VisualChangeVerdict
import com.lensqi.fieldops.data.queue.CommandState
import com.lensqi.fieldops.ui.common.SourceBadge
import com.lensqi.fieldops.ui.common.Tag
import com.lensqi.fieldops.ui.theme.Provenance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Screens 2–4 of the PRD as one scrolling inspection: capture, investigate and localize, repair and verify, receipt. */
@Composable
fun InspectionScreen(onCapture: () -> Unit, onOpenReceipt: (url: String) -> Unit, vm: InspectionViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { vm.queuePhoto(copyToCache(context, uri), origin = "RecordedFixture") }
    }
    val view = s.view

    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 12.dp)) {
                Text("Inspection ${vm.inspectionId}", style = MaterialTheme.typography.titleLarge)
                if (view != null) {
                    Text("${view.fixtureId} · ${view.assetId} · twin ${view.fixtureDigest.take(12)}…", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Stage (server)")
                        Tag(view.stage.name, stageColor(view.stage))
                        TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
                    }
                    s.viewCachedAt?.let { at ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Tag("CACHED", Provenance.Simulated)
                            Text("Offline: last server view from ${at.take(19)}Z. Nothing new is decided until the API answers.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        s.busy?.let { label ->
            item {
                Column {
                    Text(label)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = vm::cancel) { Text("Cancel") }
                }
            }
        }
        s.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }

        // Evidence
        item {
            Section("Photos", "evidence", Provenance.Evidence) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onCapture) { Text("Capture") }
                    OutlinedButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Import image") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    view?.photos?.forEach { p ->
                        Column(Modifier.size(width = 150.dp, height = 170.dp)) {
                            val name = vm.artifactFor(p.evidenceId)
                            if (name != null) AsyncImage(vm.artifactUrl(name), p.evidenceId, Modifier.size(150.dp, 94.dp), contentScale = ContentScale.Crop)
                            Text(p.evidenceId, style = MaterialTheme.typography.labelSmall)
                            Tag(p.origin.name, Provenance.Evidence)
                            p.quality?.let { q -> Text("blur ${"%.0f".format(q.blurLaplacianVariance)} · lum ${"%.0f".format(q.meanLuminance)} (signal)", style = MaterialTheme.typography.labelSmall) }
                            view?.changeAnalyses?.lastOrNull { it.photoEvidenceId == p.evidenceId }?.let { c -> Tag(c.verdict.name, changeColor(c.verdict)) }
                        }
                    }
                }
                view?.changeAnalyses?.lastOrNull { it.claimId == view.currentClaimId }?.let { c ->
                    Text("Change evidence (measured, visual) for ${c.photoEvidenceId}: ${c.reason}", style = MaterialTheme.typography.bodySmall,
                        color = changeColor(c.verdict))
                }
                s.pending.forEach { c ->
                    val text = if (c.state == CommandState.Rejected) "Rejected ${c.errorCode}: ${c.errorMessage}" else "Awaiting upload · sha ${c.sha256?.take(12)}… · ${c.source} · try ${c.attempts}"
                    Text(text, color = if (c.state == CommandState.Rejected) MaterialTheme.colorScheme.error else Provenance.Simulated, style = MaterialTheme.typography.bodySmall)
                }
                if (s.pending.any { it.state == CommandState.Pending }) TextButton(onClick = vm::syncNow) { Text("Sync now") }
            }
        }

        // Investigate (Muse)
        item {
            Section("Investigate", "inferred", Provenance.Inferred) {
                Button(onClick = vm::investigate, enabled = s.busy == null && !view?.photos.isNullOrEmpty()) { Text("Assess latest photo with Muse Spark") }
                s.assessment?.let { r ->
                    Text("model ${r.modelId} · tokens ${r.usage?.inputTokenCount ?: "?"}/${r.usage?.outputTokenCount ?: "?"} · response ${r.responseDigest.take(12)}…", style = MaterialTheme.typography.labelSmall)
                    r.assessment.hypotheses.forEach { h ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Tag(h.status.name, statusColor(h.status))
                            Text("${h.featureId}: ${h.reason}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("Proposed next action: ${r.assessment.nextAction}" + (r.assessment.requestedView?.let { " — “$it”" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // Localize (SAM)
        item {
            // Held by the view model: a lazy item that scrolls off screen loses its own state.
            val concept = s.concept ?: s.assessment?.assessment?.requestedSegmentationConcept
                ?: s.fixture?.visualRequirements?.firstOrNull { it.segmentationPrompt != null }?.segmentationPrompt.orEmpty()
            Section("Localize", "inferred", Provenance.Inferred) {
                OutlinedTextField(concept, vm::onConceptChanged, label = { Text("Concept (one noun phrase)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.segment(concept) }, enabled = s.busy == null && !view?.photos.isNullOrEmpty()) { Text("Segment with SAM 3.1") }
                s.segmentation?.let { seg ->
                    if (seg.matched) Text("${seg.model} “${seg.concept}”: ${seg.boxes.size} box(es), ${seg.masks.size} mask(s), ${"%.1f".format(seg.elapsedSeconds)} s · ${seg.label}", style = MaterialTheme.typography.labelSmall)
                    else Text("No region detected for “${seg.concept}” — honest no-match from ${seg.model}; no mask was fabricated", style = MaterialTheme.typography.bodySmall)
                    s.segmentationImage?.let { SegmentationOverlay(it, seg) }
                    seg.crop?.let { c ->
                        Text("Crop from the original pixels (not independent evidence)", style = MaterialTheme.typography.labelSmall)
                        AsyncImage(vm.artifactUrl(c.file), "crop", Modifier.fillMaxWidth(0.6f))
                    }
                }
            }
        }

        // Vendor knowledge (documented / site configured)
        s.knowledge?.let { k ->
            item {
                Section("Vendor knowledge", "documented", Provenance.Documented) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (s.knowledgeCachedAt != null) Tag("CACHED", Provenance.Simulated)
                        Text("pack ${k.packId ?: "none"} rev ${k.packRevision ?: "-"} · ${k.status} · ${k.packSha256?.take(12) ?: ""}…", style = MaterialTheme.typography.labelSmall)
                    }
                    s.knowledgeCachedAt?.let { Text("Copy saved on this device at ${it.take(19)}Z; the pack digest above identifies exactly what it was.", style = MaterialTheme.typography.bodySmall) }
                    k.facts.forEach { f ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Tag(if (f.provenance == "SiteConfigured") "site configured" else "documented", Provenance.Documented)
                                Text(f.factId, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                            }
                            Text(f.statement, style = MaterialTheme.typography.bodySmall)
                            Text("${f.citation.documentTitle} — ${f.citation.sourceLocator} · accessed ${f.citation.accessedOn}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    k.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        // Diagnostics
        item {
            Section("Diagnostics", "measured", Provenance.Measured) {
                s.fixture?.diagnostics?.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.runDiagnostic(d.diagnosticId) }, enabled = s.busy == null) { Text("Run ${d.diagnosticId}") }
                        Text("via ${d.targetAlias}", style = MaterialTheme.typography.labelSmall)
                    }
                    if (s.diagnosticsMode == DiagnosticSource.Simulated) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Tag("DEMO CONTROL", Provenance.Simulated)
                            TextButton(onClick = { vm.setSimulated(d.diagnosticId, false) }) { Text("Simulate down") }
                            TextButton(onClick = { vm.setSimulated(d.diagnosticId, true) }) { Text("Simulate up") }
                        }
                    }
                }
                view?.diagnostics?.takeLast(4)?.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SourceBadge(d.source)
                        Text("${d.result.diagnosticId}: ${when (d.result.passed) { true -> "pass"; false -> "fail"; null -> "unavailable" }} · ${d.result.normalizedValue ?: ""} · ${d.result.completedAt.take(19)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Repair
        item {
            val summary = s.workOrderText
            val claim = s.claimText
            Section("Repair", "technician", MaterialTheme.colorScheme.primary) {
                OutlinedTextField(summary, vm::onWorkOrderChanged, label = { Text("Work order") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { vm.workOrder(summary) }, enabled = s.busy == null) { Text("Draft work order") }
                OutlinedTextField(claim, vm::onClaimChanged, label = { Text("Repair claim") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.claim(claim) }, enabled = s.busy == null) { Text("Claim repair") }
                view?.currentClaimId?.let { Text("Current claim $it at ${view.repairClaimedAt?.take(19)}", style = MaterialTheme.typography.labelSmall) }
            }
        }

        // Post-repair checklist and acknowledgement
        item {
            // The server names the post-repair photo it will count; a photo rejected by change evidence is never offered.
            val postClaim = view?.acceptedPostRepairPhotoId?.let { id -> view.photos.firstOrNull { it.evidenceId == id } }
            Section("Post-repair review", "technician", MaterialTheme.colorScheme.primary) {
                if (view == null || postClaim == null) Text("Capture a new photo after the repair claim to review it. Re-submitted or unchanged photos are not accepted.", style = MaterialTheme.typography.bodySmall)
                else {
                    Text("Reviewing ${postClaim.evidenceId}", style = MaterialTheme.typography.labelSmall)
                    s.fixture?.visualRequirements?.filter { it.mandatory }?.forEach { req ->
                        val current = view.checklist.firstOrNull { it.requirementId == req.requirementId }?.status
                        Text(req.requirementId, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(ChecklistStatus.Satisfied, ChecklistStatus.NotSatisfied, ChecklistStatus.Unknown).forEach { st ->
                                FilterChip(selected = current == st, onClick = { vm.review(req.requirementId, st, postClaim.evidenceId) }, label = { Text(st.name) }, enabled = s.busy == null)
                            }
                        }
                    }
                    Button(onClick = vm::acknowledge, enabled = s.busy == null && !view.technicianAcknowledged) {
                        Text(if (view.technicianAcknowledged) "Acknowledged" else "I reviewed this evidence")
                    }
                }
            }
        }

        // Verify
        item {
            Section("Verify", "server decision", Provenance.Measured) {
                Button(onClick = vm::verify, enabled = s.busy == null && view?.stage == InspectionStage.ReadyToVerify) { Text("Verify repair") }
                view?.lastDecision?.let { d ->
                    val verified = d.result == VerificationResult.Verified
                    Column(
                        Modifier.fillMaxWidth().background((if (verified) Provenance.Evidence else Provenance.Denied).copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(d.result.name, style = MaterialTheme.typography.titleMedium, color = if (verified) Provenance.Evidence else Provenance.Denied)
                            d.outcomeKind?.let { k -> Tag(k.name, if (k == VerificationOutcomeKind.VerifiedCompleteLive) Provenance.Measured else Provenance.Simulated) }
                        }
                        d.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        if (d.verifiedClaims.isNotEmpty()) Text("Verified: ${d.verifiedClaims.joinToString()}", style = MaterialTheme.typography.bodySmall)
                        Text("policy ${d.policyVersion}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Receipt
        item {
            Section("Receipt", "hash-chained", Provenance.Documented) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onOpenReceipt(vm.receiptUrl()) }) { Text("Open receipt") }
                    OutlinedButton(onClick = vm::checkReceipt, enabled = s.busy == null) { Text("Verify receipt") }
                    TextButton(onClick = { shareText(context, vm.receiptJsonUrl()) }) { Text("Share") }
                }
                s.receiptCheck?.let { r ->
                    Text(if (r.isValid) "Receipt verifies: ${r.checks.size} checks passed" else "Receipt FAILS: ${r.failures.joinToString()}",
                        color = if (r.isValid) Provenance.Evidence else Provenance.Denied, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Text(" ") }
    }
}

@Composable
private fun Section(title: String, provenance: String, color: Color, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Tag(provenance, color)
            }
            content()
        }
    }
}

private fun stageColor(stage: InspectionStage) = when (stage) {
    InspectionStage.VerifiedComplete -> Provenance.Evidence
    InspectionStage.VerificationFailed, InspectionStage.Inconclusive -> Provenance.Denied
    else -> Provenance.Measured
}

private fun changeColor(v: VisualChangeVerdict) = when (v) {
    VisualChangeVerdict.Changed -> Provenance.Measured
    VisualChangeVerdict.DuplicateImage, VisualChangeVerdict.NoVisualChange -> Provenance.Denied
    VisualChangeVerdict.Unscorable -> Color.Gray
}

private fun statusColor(status: ObservationStatus) = when (status) {
    ObservationStatus.Observed -> Provenance.Evidence
    ObservationStatus.Suspected -> Provenance.Simulated
    ObservationStatus.Unknown -> Color.Gray
    ObservationStatus.Conflicting -> Provenance.Denied
}

private suspend fun copyToCache(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
    val file = File(context.cacheDir, "import-${System.nanoTime()}")
    context.contentResolver.openInputStream(uri).use { input -> file.outputStream().use { requireNotNull(input).copyTo(it) } }
    file
}

private fun shareText(context: Context, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
    context.startActivity(android.content.Intent.createChooser(send, "Share receipt"))
}
