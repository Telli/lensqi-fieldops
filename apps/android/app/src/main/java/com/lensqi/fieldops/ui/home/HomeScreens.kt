package com.lensqi.fieldops.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lensqi.fieldops.data.api.InspectionStage
import com.lensqi.fieldops.data.api.InspectionSummary
import com.lensqi.fieldops.ui.common.Dot
import com.lensqi.fieldops.ui.common.EvidenceBadge
import com.lensqi.fieldops.ui.common.Kpi
import com.lensqi.fieldops.ui.common.Panel
import com.lensqi.fieldops.ui.common.PrimaryButton
import com.lensqi.fieldops.ui.common.SectionTitle
import com.lensqi.fieldops.ui.common.SourceBadge
import com.lensqi.fieldops.ui.common.stageLabel
import com.lensqi.fieldops.ui.common.stageState
import com.lensqi.fieldops.ui.theme.Fo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val dayFmt = DateTimeFormatter.ofPattern("MMM d, HH:mm")

fun whenText(iso: String?): String = runCatching {
    val t = Instant.parse(iso!!.replace(Regex("\\+00:00$"), "Z")).atZone(ZoneId.systemDefault())
    if (t.toLocalDate() == LocalDate.now()) "Today ${t.format(timeFmt)}" else t.format(dayFmt)
}.getOrDefault(iso ?: "—")

private fun greeting() = when (java.time.LocalTime.now().hour) { in 0..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }

private fun open(s: InspectionSummary) = s.stage != InspectionStage.VerifiedComplete && s.stage != InspectionStage.Inconclusive

@Composable
fun ConnBadge(reachable: Boolean?) {
    val on = reachable != false
    Row(
        Modifier.border(1.dp, if (on) Fo.Border else Fo.Warning.copy(alpha = 0.4f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(if (on) Icons.Outlined.Wifi else Icons.Outlined.CloudOff, null, tint = if (on) Fo.Muted else Fo.Warning, modifier = Modifier.size(12.dp))
        Text(if (on) "Connected" else "Offline", color = if (on) Fo.Muted else Fo.Warning, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun InspectionCard(s: InspectionSummary, assetName: String?, onOpen: (String) -> Unit) {
    Panel(Modifier.clickable { onOpen(s.inspectionId) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(whenText(s.updatedAt), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            EvidenceBadge(stageState(s.stage), stageLabel(s.stage))
        }
        Text(s.workOrder ?: assetName ?: s.assetId, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text("${if (s.workOrder != null) assetName ?: s.assetId else s.fixtureId} · ${s.photos} photo${if (s.photos == 1) "" else "s"}" + if (s.diagnostics > 0) " · ${s.diagnostics} check${if (s.diagnostics == 1) "" else "s"}" else "", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun HomeScreen(vm: HomeViewModel, onStart: () -> Unit, onOpen: (String) -> Unit, onAll: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val active = s.inspections.filter(::open)
    val toVerify = s.inspections.filter { it.stage == InspectionStage.ReadyToVerify || it.stage == InspectionStage.VerificationFailed }
    val doneToday = s.inspections.count { it.stage == InspectionStage.VerifiedComplete && whenText(it.updatedAt).startsWith("Today") }
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(s.fixtures.firstOrNull()?.fixtureId ?: "LensQi FieldOps", style = MaterialTheme.typography.bodySmall)
                    Text(greeting(), style = MaterialTheme.typography.headlineSmall)
                }
                ConnBadge(s.reachable)
            }
        }
        s.cachedAt?.let { at -> item { Text("Offline: showing the list saved at ${whenText(at)}. Captures still queue on this phone.", color = Fo.Warning, style = MaterialTheme.typography.bodySmall) } }
        item { PrimaryButton("Start an inspection", onStart, icon = Icons.Outlined.QrCodeScanner, modifier = Modifier.height(56.dp)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Kpi("Active", "${active.size}", Fo.Primary, Modifier.weight(1f))
                Kpi("To verify", "${toVerify.size}", Fo.Warning, Modifier.weight(1f))
                Kpi("Done today", "$doneToday", Fo.Success, Modifier.weight(1f))
            }
        }
        val requested = s.inspections.filter { it.openEvidenceRequest != null }
        if (requested.isNotEmpty()) {
            item { SectionTitle("Evidence requested") }
            items(requested.take(3), key = { "r-" + it.inspectionId }) { r ->
                val q = r.openEvidenceRequest!!
                Panel(Modifier.clickable { onOpen(r.inspectionId) }, border = Fo.Warning.copy(alpha = 0.45f), background = Fo.Warning.copy(alpha = 0.08f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.CameraAlt, null, tint = Fo.Warning)
                        Column(Modifier.weight(1f)) {
                            Text(q.note, fontWeight = FontWeight.Medium)
                            Text("${vm.assetName(r.assetId) ?: r.assetId} · ${q.requestedBy}, ${whenText(q.requestedAt)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = Fo.Muted)
                    }
                }
            }
        }
        if (toVerify.isNotEmpty()) {
            item { SectionTitle("Work requiring verification") }
            items(toVerify.take(3), key = { "v-" + it.inspectionId }) { v ->
                Panel(Modifier.clickable { onOpen(v.inspectionId) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(v.workOrder ?: vm.assetName(v.assetId) ?: v.assetId, fontWeight = FontWeight.Medium)
                            Text(if (v.stage == InspectionStage.VerificationFailed) "Last verification failed" else "Repair claimed · verification not yet run", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = Fo.Muted)
                    }
                }
            }
        }
        item { SectionTitle("Open inspections") { Text("All", color = Fo.Primary, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onAll)) } }
        if (active.isEmpty() && !s.loading) item { Panel { Text("No open inspections. Start one by scanning the equipment in front of you.", style = MaterialTheme.typography.bodySmall) } }
        items(active.take(4), key = { it.inspectionId }) { InspectionCard(it, vm.assetName(it.assetId), onOpen) }
        if (s.fixtures.isNotEmpty()) {
            item { SectionTitle("Assets") }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    s.fixtures.flatMap { f -> f.assets.map { f.fixtureId to it } }.forEach { (_, a) ->
                        val latest = s.inspections.firstOrNull { it.assetId == a.assetId }
                        val (c, h) = when {
                            latest == null -> Fo.Muted to "Unknown"
                            latest.stage == InspectionStage.VerifiedComplete -> Fo.Success to "Healthy"
                            latest.stage in listOf(InspectionStage.FaultConfirmed, InspectionStage.VerificationFailed, InspectionStage.AwaitingRepair) -> Fo.Failure to "Fault"
                            else -> Fo.Warning to "Attention"
                        }
                        Panel(Modifier.width(150.dp), padding = 12) {
                            Text(a.assetId, style = MaterialTheme.typography.bodySmall)
                            Text(a.displayName, fontWeight = FontWeight.Medium)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Dot(c); Text(h, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun InspectionsScreen(vm: HomeViewModel, onOpen: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val openOnes = s.inspections.filter(::open)
    val closed = s.inspections.filterNot(::open)
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Inspections", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp)) }
        if (openOnes.isNotEmpty()) item { SectionTitle("Open · ${openOnes.size}") }
        items(openOnes, key = { it.inspectionId }) { InspectionCard(it, vm.assetName(it.assetId), onOpen) }
        if (closed.isNotEmpty()) item { SectionTitle("Closed · ${closed.size}") }
        items(closed.take(30), key = { "c-" + it.inspectionId }) { InspectionCard(it, vm.assetName(it.assetId), onOpen) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun StartScreen(vm: HomeViewModel, onCreated: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Start an inspection", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp)) }
        item { Text("Pick the equipment from its asset label. LensQi checks it against the site's expected setup.", style = MaterialTheme.typography.bodySmall) }
        s.error?.let { item { Text(it, color = Fo.Failure, style = MaterialTheme.typography.bodySmall) } }
        s.fixtures.forEach { f ->
            item { SectionTitle(f.fixtureId) }
            items(f.assets, key = { it.assetId }) { a ->
                Panel(Modifier.clickable(enabled = s.creating == null) { vm.create(f.fixtureId, a.assetId, onCreated) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(40.dp).background(Fo.Accent, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Inventory2, null, tint = Fo.AccentFg) }
                        Column(Modifier.weight(1f)) {
                            Text("Inspect ${a.displayName}", fontWeight = FontWeight.Medium)
                            Text("${a.assetId} · ${a.kind.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (s.creating == a.assetId) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.ChevronRight, null, tint = Fo.Muted)
                    }
                }
            }
        }
        if (s.fixtures.isEmpty()) item { Panel { Text(if (s.reachable == false) "The server is unreachable, so assets can't be loaded. Check the connection in Profile." else "Loading assets…", style = MaterialTheme.typography.bodySmall) } }
    }
}

@Composable
fun AssetsScreen(vm: HomeViewModel, onOpenAsset: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Assets", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp)) }
        s.fixtures.forEach { f ->
            item { SectionTitle(f.fixtureId) }
            items(f.assets, key = { "a-" + it.assetId }) { a ->
                val n = s.inspections.count { it.assetId == a.assetId }
                Panel(Modifier.clickable { onOpenAsset(a.assetId) }) {
                    Text(a.displayName, fontWeight = FontWeight.Medium)
                    Text("${a.assetId} · $n inspection${if (n == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(vm: HomeViewModel, onOpen: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var existing by rememberSaveable { mutableStateOf("") }
    val field = OutlinedTextFieldDefaults.colors(focusedBorderColor = Fo.Primary, unfocusedBorderColor = Fo.Border)
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Profile", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp)) }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Inspection server", modifier = Modifier.weight(1f), color = Fo.Muted); ConnBadge(s.reachable) }
                s.health?.let { h ->
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Equipment checks", modifier = Modifier.weight(1f), color = Fo.Muted); SourceBadge(h.diagnosticsMode) }
                    Text("${h.fixtures} site · ${h.packs} knowledge packs", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            OutlinedTextField(s.apiBase, vm::onBaseChanged, label = { Text("Server address") }, singleLine = true, colors = field, modifier = Modifier.fillMaxWidth())
        }
        item { PrimaryButton("Save and check connection", { vm.saveBase() }, busy = s.loading) }
        s.error?.let { item { Text(it, color = Fo.Failure, style = MaterialTheme.typography.bodySmall) } }
        item {
            // Works offline: the screen shows the device copy of the inspection until the server answers.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(existing, { existing = it.trim() }, label = { Text("Open inspection (I-…)") }, singleLine = true, colors = field, modifier = Modifier.weight(1f))
                PrimaryButton("Open", { onOpen(existing) }, enabled = existing.startsWith("I-"), modifier = Modifier.width(96.dp))
            }
        }
        item { Text("Simulated checks are labelled everywhere and can never produce a live verification. AI observations are suggestions; a repair is verified only when a new photo, your review and a fresh equipment check all agree.", style = MaterialTheme.typography.bodySmall) }
    }
}
