package com.lensqi.fieldops.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import coil.compose.AsyncImage
import com.lensqi.fieldops.ui.common.LensQiMark
import com.lensqi.fieldops.ui.common.needsYou
import com.lensqi.fieldops.ui.common.reasonText
import com.lensqi.fieldops.ui.theme.Eyebrow
import com.lensqi.fieldops.ui.theme.FoDark
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
import com.lensqi.fieldops.ui.common.SecondaryButton
import com.lensqi.fieldops.ui.common.SectionTitle
import com.lensqi.fieldops.ui.common.SourceBadge
import com.lensqi.fieldops.ui.common.stageLabel
import com.lensqi.fieldops.ui.common.stageState
import com.lensqi.fieldops.ui.theme.Appearance
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
fun HomeScreen(vm: HomeViewModel, onStart: () -> Unit, onAssets: () -> Unit, onOpen: (String) -> Unit, onAll: () -> Unit, onProfile: () -> Unit = {}, onScan: () -> Unit = {}) {
    val s by vm.state.collectAsStateWithLifecycle()
    val queue = needsYou(s.inspections)
    val focus = queue.firstOrNull { it.inspectionId == s.focusId } ?: queue.firstOrNull { it.photos > 0 }
    val rest = queue.filter { it != focus }.take(3)
    LazyColumn(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(greeting(), style = MaterialTheme.typography.bodySmall)
                    Text(if (focus != null) "Pick up where you left off" else "Ready when you are", fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                }
                ConnBadge(s.reachable)
                Box(Modifier.size(38.dp).clip(CircleShape).background(Fo.Surface).border(1.dp, Fo.Border, CircleShape).clickable(onClick = onProfile), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Person, "Settings", modifier = Modifier.size(18.dp))
                }
            }
        }
        s.cachedAt?.let { at -> item { Text("Offline: showing the list saved at ${whenText(at)}. Captures still queue on this phone.", color = Fo.Warning, style = MaterialTheme.typography.bodySmall) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onStart) { Text("Start inspection") }
                TextButton(onClick = onScan) { Text("Scan label") }
                TextButton(onClick = onAssets) { Text("Assets") }
            }
        }
        item { if (focus != null) FocusHero(focus, s.focusPhotoUrl.takeIf { s.focusId == focus.inspectionId }, vm.assetName(focus.assetId)) { onOpen(focus.inspectionId) } else ScanHero(onStart) }
        if (rest.isNotEmpty()) {
            item { Text("UP NEXT", style = Eyebrow, modifier = Modifier.padding(start = 4.dp)) }
            item {
                Column(Modifier.clip(RoundedCornerShape(24.dp)).background(Fo.Surface).border(1.dp, Fo.Border, RoundedCornerShape(24.dp))) {
                    rest.forEachIndexed { k, r ->
                        if (k > 0) HorizontalDivider(color = Fo.Border)
                        val (icon, tone) = when {
                            r.openEvidenceRequest != null -> Icons.Outlined.CameraAlt to Fo.Warning
                            r.stage == InspectionStage.VerificationFailed -> Icons.Outlined.GppBad to Fo.Failure
                            r.stage == InspectionStage.ReadyToVerify -> Icons.Outlined.VerifiedUser to Fo.Primary
                            r.stage == InspectionStage.AwaitingRepair || r.stage == InspectionStage.FaultConfirmed -> Icons.Outlined.Build to Fo.Warning
                            else -> Icons.Outlined.Search to Fo.Warning
                        }
                        Row(Modifier.fillMaxWidth().clickable { onOpen(r.inspectionId) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(40.dp).background(tone.copy(alpha = 0.13f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tone, modifier = Modifier.size(19.dp)) }
                            Column(Modifier.weight(1f)) {
                                Text(reasonText(r), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${vm.assetName(r.assetId) ?: r.assetId} · ${whenText(r.updatedAt)}", style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Outlined.ChevronRight, null, tint = Fo.Muted)
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clickable(onClick = onAll).padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(if (queue.size > rest.size + 1) "All inspections · ${queue.size} waiting" else "All inspections", color = Fo.Muted, fontSize = 14.sp)
                Icon(Icons.Outlined.ChevronRight, null, tint = Fo.Muted, modifier = Modifier.size(18.dp))
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

/** The photo LensQi is working on and the one thing it needs, on dark glass whatever the theme. */
@Composable
private fun FocusHero(s: InspectionSummary, photoUrl: String?, assetName: String?, onOpen: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(FoDark.Overlay).clickable(onClick = onOpen)) {
        if (photoUrl != null) AsyncImage(photoUrl, "Latest photo", Modifier.fillMaxWidth().aspectRatio(4f / 3f), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.QrCodeScanner, null, tint = FoDark.Muted, modifier = Modifier.size(40.dp)) }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            GlassChip(assetName ?: s.assetId)
            GlassChip(stageLabel(s.stage))
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, FoDark.Overlay.copy(alpha = 0.92f), FoDark.Overlay)))
                .padding(start = 16.dp, end = 16.dp, top = 36.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LensQiMark()
            Column(Modifier.weight(1f)) {
                Text("Next", fontSize = 12.sp, color = FoDark.Muted)
                Text(reasonText(s), fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = FoDark.Foreground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = FoDark.Muted)
        }
    }
}

/** Nothing in flight: the camera is the whole story. */
@Composable
private fun ScanHero(onStart: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(28.dp)).background(FoDark.Overlay).clickable(onClick = onStart), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(96.dp)) {
            val c = FoDark.Foreground.copy(alpha = 0.8f); val l = 22.dp.toPx(); val w = 2.dp.toPx()
            listOf(Offset(0f, 0f) to Offset(1f, 1f), Offset(size.width, 0f) to Offset(-1f, 1f), Offset(0f, size.height) to Offset(1f, -1f), Offset(size.width, size.height) to Offset(-1f, -1f)).forEach { (o, d) ->
                drawLine(c, o, Offset(o.x + d.x * l, o.y), w); drawLine(c, o, Offset(o.x, o.y + d.y * l), w)
            }
            drawCircle(FoDark.Primary, 3.dp.toPx())
        }
        Row(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LensQiMark()
            Column {
                Text("Point at the equipment.", fontSize = 12.sp, color = FoDark.Muted)
                Text("I'll identify it and outline what matters.", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = FoDark.Foreground)
            }
        }
    }
}

@Composable
private fun GlassChip(text: String) = Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = FoDark.Foreground,
    modifier = Modifier.clip(RoundedCornerShape(50)).background(FoDark.Overlay.copy(alpha = 0.65f)).padding(horizontal = 10.dp, vertical = 5.dp))

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
fun StartScreen(vm: HomeViewModel, onScan: () -> Unit = {}, onCreated: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Start an inspection", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp)) }
        item { Text("Scan the asset label, or pick the equipment below. LensQi checks it against the site's expected setup.", style = MaterialTheme.typography.bodySmall) }
        item { SecondaryButton("Scan the asset label", onScan, Modifier.fillMaxWidth(), enabled = s.fixtures.isNotEmpty(), icon = Icons.Outlined.QrCodeScanner) }
        s.fixturesCachedAt?.let { at -> item { AssetsCachedNote(at) } }
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
        if (s.fixtures.isEmpty()) item {
            Panel {
                Text(when {
                    s.loading -> "Loading assets…"
                    s.reachable == false -> "The server is unreachable, so assets can't be loaded. Check the connection in Settings."
                    s.fixturesCachedAt != null -> "The server did not answer for the site records and no copy is saved on this phone. Refresh to try again."
                    else -> "The site records list no assets. Ask the site administrator to add the equipment before inspecting it."
                }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** The assets on screen are the copy saved on this phone: the server did not answer for them this time. */
@Composable
private fun AssetsCachedNote(fetchedAt: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Icon(Icons.Outlined.CloudOff, null, tint = Fo.Warning, modifier = Modifier.size(16.dp))
    Text("Assets from the copy saved ${whenText(fetchedAt)}. The server did not answer for the site records; an asset added or removed since may not show.", color = Fo.Warning, style = MaterialTheme.typography.bodySmall)
}

@Composable
fun AssetsScreen(vm: HomeViewModel, onOpenAsset: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Assets", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp)) }
        s.fixturesCachedAt?.let { at -> item { AssetsCachedNote(at) } }
        s.fixtures.forEach { f ->
            item { SectionTitle(f.fixtureId) }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Fo.Surface).border(1.dp, Fo.Border, RoundedCornerShape(18.dp))) {
                    f.assets.forEachIndexed { i, a ->
                        if (i > 0) HorizontalDivider(color = Fo.Border)
                        val latest = s.inspections.firstOrNull { it.assetId == a.assetId }
                        val dot = when (latest?.stage) {
                            null -> Fo.Muted
                            InspectionStage.VerifiedComplete -> Fo.Success
                            InspectionStage.FaultConfirmed, InspectionStage.VerificationFailed, InspectionStage.AwaitingRepair -> Fo.Failure
                            else -> Fo.Warning
                        }
                        Row(Modifier.fillMaxWidth().clickable { onOpenAsset(a.assetId) }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box {
                                Box(Modifier.size(44.dp).background(Fo.Primary.copy(alpha = 0.12f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Inventory2, null, tint = Fo.Primary, modifier = Modifier.size(20.dp))
                                }
                                Box(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).size(11.dp).background(Fo.Surface, CircleShape).padding(2.dp).background(dot, CircleShape))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(a.displayName, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(latest?.let { "${stageLabel(it.stage)} · ${whenText(it.updatedAt)}" } ?: "Not inspected yet", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Fo.Muted)
                        }
                    }
                }
            }
        }
    }
}

/** Height and tools, so plans and coaching fit the person: someone 6'2" can often reach a ceiling detector standing. */
@Composable
private fun AboutYou(vm: HomeViewModel, field: androidx.compose.material3.TextFieldColors) {
    val person by vm.person.collectAsStateWithLifecycle(com.lensqi.fieldops.data.settings.PersonProfile())
    var height by rememberSaveable(person.heightCm) { mutableStateOf(person.heightCm?.let { "$it cm" }.orEmpty()) }
    var tools by remember(person.tools) { mutableStateOf(person.tools) }
    var note by remember { mutableStateOf<String?>(null) }
    Panel {
        Text("About you", fontWeight = FontWeight.SemiBold)
        Text("Used to fit plans and coaching to you, for example whether you need a ladder. Stays on this phone and goes with each new task.", style = MaterialTheme.typography.bodySmall, color = Fo.Muted)
        OutlinedTextField(height, { height = it; note = null }, label = { Text("Your height (6'2\" or 188 cm)") }, singleLine = true, colors = field, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.lensqi.fieldops.data.settings.PersonProfile.ToolChoices.forEach { t ->
                val on = t in tools
                Box(Modifier.background(if (on) Fo.Elevated else Fo.Surface, RoundedCornerShape(50)).border(1.dp, if (on) Fo.Primary else Fo.Border, RoundedCornerShape(50))
                    .clickable { tools = if (on) tools - t else tools + t; note = null }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text((if (on) "✓ " else "") + "I have a $t", fontSize = 13.sp, color = if (on) Fo.Foreground else Fo.Muted)
                }
            }
        }
        person.standingReachCm?.let { Text("Standing reach about $it cm, estimated from your height.", style = MaterialTheme.typography.bodySmall, color = Fo.Muted) }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Saved")) Fo.Success else Fo.Failure) }
        SecondaryButton("Save", { vm.savePerson(height, tools) { ok -> note = if (ok) "Saved" else "Couldn't read that height. Try 6'2\" or 188 cm." } }, Modifier.fillMaxWidth())
    }
}

@Composable
fun ProfileScreen(vm: HomeViewModel, onOpen: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val s by vm.state.collectAsStateWithLifecycle()
    var existing by rememberSaveable { mutableStateOf("") }
    val field = OutlinedTextFieldDefaults.colors(focusedBorderColor = Fo.Primary, unfocusedBorderColor = Fo.Border)
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp)) }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Inspection server", modifier = Modifier.weight(1f), color = Fo.Muted); ConnBadge(s.reachable) }
                s.health?.let { h ->
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Equipment checks", modifier = Modifier.weight(1f), color = Fo.Muted); SourceBadge(h.diagnosticsMode) }

                }
            }
        }
        item {
            OutlinedTextField(s.apiBase, vm::onBaseChanged, label = { Text("Server address") }, singleLine = true, colors = field, modifier = Modifier.fillMaxWidth())
        }
        item { PrimaryButton("Save and check connection", { vm.saveBase {
            val intent = android.content.Intent(context, com.lensqi.fieldops.ui.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
        } }, busy = s.loading) }
        item { AboutYou(vm, field) }
        item {
            com.lensqi.fieldops.ui.common.Expandable("Diagnostics") {
                val enabled by vm.diagnostics.collectAsStateWithLifecycle(false)
                val history by com.lensqi.fieldops.data.diagnostics.DiagnosticHistory.entries.collectAsStateWithLifecycle()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show technical details", modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(enabled, vm::setDiagnostics,
                        modifier = Modifier.semantics { contentDescription = "Show technical details" })
                }
                Text("Off by default. Shows camera performance and technical details on this device. Does not change checks or upload anything.", style = MaterialTheme.typography.bodySmall)
                s.health?.let { h -> Text("${h.fixtures} fixtures · ${h.packs} knowledge packs", style = MaterialTheme.typography.bodySmall) }
                Text("Recent operations", style = MaterialTheme.typography.titleSmall)
                if (history.isEmpty()) Text("No diagnostic errors recorded this session.")
                history.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                androidx.compose.material3.TextButton(onClick = com.lensqi.fieldops.data.diagnostics.DiagnosticHistory::clear) { Text("Clear diagnostic history") }
            }
        }
        item { SectionTitle("Appearance") }
        item {
            val current by vm.appearance.collectAsStateWithLifecycle(Appearance.System)
            Row(Modifier.fillMaxWidth().background(Fo.Surface, RoundedCornerShape(10.dp)).border(1.dp, Fo.Border, RoundedCornerShape(10.dp)).padding(3.dp)) {
                Appearance.entries.forEach { a ->
                    val on = a == current
                    Box(
                        Modifier.weight(1f).height(38.dp).background(if (on) Fo.Elevated else Fo.Surface, RoundedCornerShape(8.dp)).clickable { vm.setAppearance(a) },
                        contentAlignment = Alignment.Center,
                    ) { Text(a.name, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) Fo.Foreground else Fo.Muted) }
                }
            }
        }
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
