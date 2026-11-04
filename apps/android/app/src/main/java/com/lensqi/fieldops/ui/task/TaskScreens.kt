package com.lensqi.fieldops.ui.task

import com.lensqi.fieldops.ui.common.Expandable

import com.lensqi.fieldops.ui.common.TechnicianText

import com.lensqi.fieldops.wearables.SpokenFeedbackGate
import com.lensqi.fieldops.voice.SpeechOutput
import com.lensqi.fieldops.voice.mute
import com.lensqi.fieldops.voice.unmute
import kotlinx.coroutines.delay
import com.lensqi.fieldops.data.queue.CommandState
import androidx.compose.runtime.DisposableEffect
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lensqi.fieldops.data.api.Fixture
import com.lensqi.fieldops.data.api.RiskLevel
import com.lensqi.fieldops.data.api.StepStatus
import com.lensqi.fieldops.data.api.StepVerdict
import com.lensqi.fieldops.data.api.TaskMode
import com.lensqi.fieldops.data.api.TaskPhase
import com.lensqi.fieldops.data.api.TaskStep
import com.lensqi.fieldops.data.api.TaskSummary
import com.lensqi.fieldops.data.api.checkPhotoNote
import com.lensqi.fieldops.ui.camera.CameraHandle
import com.lensqi.fieldops.ui.camera.CameraScreen
import com.lensqi.fieldops.ui.common.AiCard
import com.lensqi.fieldops.ui.common.LensQiMark
import com.lensqi.fieldops.ui.common.Panel
import com.lensqi.fieldops.ui.common.PrimaryButton
import com.lensqi.fieldops.ui.common.SectionTitle
import com.lensqi.fieldops.ui.common.stageLabel
import com.lensqi.fieldops.data.api.InspectionStage
import com.lensqi.fieldops.data.api.VerificationOutcomeKind
import com.lensqi.fieldops.ui.theme.Fo
import com.lensqi.fieldops.ui.theme.FoDark
import com.lensqi.fieldops.voice.HandsFreePolicy
import com.lensqi.fieldops.voice.NOTHING_HEARD
import com.lensqi.fieldops.voice.VoiceCommand
import com.lensqi.fieldops.voice.parseCommand
import com.lensqi.fieldops.voice.rememberHandsFree
import com.lensqi.fieldops.voice.rememberVoiceInput
import com.lensqi.fieldops.voice.speechNotice
import androidx.compose.material.icons.outlined.Hearing
import java.io.File

fun verdictText(v: StepVerdict) = when (v) { StepVerdict.LooksDone -> "Looks done"; StepVerdict.NotYet -> "Not yet"; StepVerdict.CantTell -> "Can't tell" }
private fun verdictColor(v: StepVerdict) = when (v) { StepVerdict.LooksDone -> Fo.Success; StepVerdict.NotYet -> Fo.Warning; StepVerdict.CantTell -> Fo.Muted }
private fun riskColor(r: RiskLevel) = when (r) { RiskLevel.Low -> Fo.Success; RiskLevel.Moderate -> Fo.Warning; RiskLevel.High -> Fo.Failure }

/**
 * Tap-to-mute, the same switch the "mute" and "unmute" voice commands flip. [muted] is the screen's view of
 * [SpeechOutput.enabled]; the screen sets it from both the tap and the command, so the icon follows either.
 */
@Composable
private fun MuteButton(muted: Boolean, onToggle: () -> Unit) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .4f)).border(1.dp, FoDark.Border, RoundedCornerShape(12.dp)).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
        Icon(if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp, if (muted) "Turn voice on" else "Mute voice", tint = if (muted) FoDark.Muted else FoDark.Foreground, modifier = Modifier.size(20.dp))
    }
}

/**
 * The hands-free switch, beside the mute button: a pill that also says when the microphone is open. Tapping the mic
 * button while hands-free is on goes through the same switch, so a tap always ends the open listen.
 */
@Composable
private fun HandsFreeChip(on: Boolean, listening: Boolean, onToggle: () -> Unit) {
    val tint = if (on) FoDark.Primary else FoDark.Foreground
    Row(Modifier.height(36.dp).clip(RoundedCornerShape(12.dp)).background(if (on) FoDark.Primary.copy(alpha = .22f) else Color.Black.copy(alpha = .4f))
        .border(1.dp, if (on) FoDark.Primary else FoDark.Border, RoundedCornerShape(12.dp))
        .clickable(onClickLabel = if (on) "Turn hands-free off" else "Hands-free: keep listening", onClick = onToggle).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Outlined.Hearing, null, tint = tint, modifier = Modifier.size(16.dp))
        Text(if (on && listening) "Hands-free · listening" else "Hands-free", color = tint, fontSize = 11.sp)
    }
}

/** Where the microphone's audio goes; shown with the controls so it is read before it is relied on. */
@Composable
private fun SpeechBadge(onDevice: Boolean) =
    Text(speechNotice(onDevice), color = FoDark.Muted, fontSize = 11.sp, lineHeight = 14.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 12.dp, vertical = 6.dp))

@Composable
private fun MicButton(listening: Boolean, onClick: () -> Unit, size: Int = 48) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(16.dp)).background(if (listening) Fo.Primary else Fo.Surface).border(1.dp, Fo.Border, RoundedCornerShape(16.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(if (listening) Icons.Outlined.Stop else Icons.Outlined.Mic, if (listening) "Stop listening" else "Speak", tint = if (listening) Fo.OnPrimary else Fo.Foreground)
    }
}

@Composable
fun TaskRow(t: TaskSummary, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val tech = t.mode == TaskMode.Technician
        Box(Modifier.size(40.dp).background(if (tech) Fo.Warning.copy(alpha = .14f) else Fo.Primary.copy(alpha = .14f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(when (t.mode) { TaskMode.Diy -> Icons.Outlined.AutoAwesome; TaskMode.Freeform -> Icons.Outlined.CameraAlt; TaskMode.Technician -> Icons.Outlined.Build }, null, tint = if (tech) Fo.Warning else Fo.Primary)
        }
        Column(Modifier.weight(1f)) {
            Text(t.title ?: t.goal, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(taskStatus(t), style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = Fo.Muted)
    }
}

/** Two ways in: say what you want to do yourself, or go to technician work. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TaskHomeScreen(vm: TaskListViewModel, onPlan: (String) -> Unit, onDetails: (String) -> Unit, onJustAsk: () -> Unit, onTechnician: () -> Unit, onOpen: (TaskSummary) -> Unit, onProfile: () -> Unit, onTeaching: () -> Unit = {}) {
    val s by vm.state.collectAsStateWithLifecycle()
    var goal by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(TaskFilter.All) }
    var search by remember { mutableStateOf("") }
    var planning by remember { mutableStateOf(false) }
    val writing = planning && s.creating
    val voice = rememberVoiceInput { goal = it }
    LaunchedEffect(Unit) { vm.refresh() }
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("What are we doing today?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onProfile) { Text("Settings", color = Fo.Muted) }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(FoDark.Background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    LensQiMark()
                    Column {
                        Text("Do it yourself", color = FoDark.Foreground, fontWeight = FontWeight.SemiBold)
                        Text("I'll plan it, then guide you step by step with the camera and voice.", color = FoDark.Muted, fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(if (voice.listening) voice.heard.ifEmpty { goal } else goal, { goal = it }, Modifier.weight(1f),
                        placeholder = { Text("e.g. Change the oil in my 2025 Civic") }, minLines = 2, maxLines = 3,
                        // The card is always dark; the field's text must be light in both app themes.
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = FoDark.Foreground, unfocusedTextColor = FoDark.Foreground,
                            focusedPlaceholderColor = FoDark.Muted, unfocusedPlaceholderColor = FoDark.Muted, focusedBorderColor = FoDark.Primary, unfocusedBorderColor = FoDark.Border, cursorColor = FoDark.Primary))
                    if (voice.supported) MicButton(voice.listening, { if (voice.listening) voice.stop() else voice.start() })
                }
                SpeechBadge(false)
                voice.error?.let { Text(it, color = Fo.Warning, fontSize = 12.sp) }
                if (!s.creating) s.error?.let { Text(it, color = Fo.Warning, fontSize = 12.sp) }
                PrimaryButton(if (writing) "Writing your plan…" else "Plan it", { planning = true; onPlan(goal.trim()) }, Modifier.fillMaxWidth(), enabled = goal.trim().length >= 3 && !s.creating, icon = Icons.Outlined.AutoAwesome, busy = writing)
                if (writing) Text("This takes up to half a minute; the plan is checked for safety before you see it.", color = FoDark.Muted, fontSize = 12.sp)
                else TextButton(onClick = { onDetails(goal.trim()) }) { Text("Add details or a manual photo", color = FoDark.Primary, fontSize = 12.sp) }
            }
        }
        item {
            Panel(Modifier.clickable(enabled = !s.creating, onClick = onJustAsk)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).background(Fo.Primary.copy(alpha = .14f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        if (s.creating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.CameraAlt, null, tint = Fo.Primary)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Just ask", fontWeight = FontWeight.SemiBold)
                        Text("Point the camera at anything and ask. No plan needed: an error on a screen, a part you don't recognise, a quick fix.", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = Fo.Muted)
                }
            }
        }
        item {
            Panel(Modifier.clickable(onClick = onTechnician)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).background(Fo.Warning.copy(alpha = .14f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Build, null, tint = Fo.Warning) }
                    Column(Modifier.weight(1f)) {
                        Text("Technician", fontWeight = FontWeight.SemiBold)
                        Text("Installs and repairs on site records, equipment checks and inspection records.", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = Fo.Muted)
                }
            }
        }
        item { TextButton(onClick = onTeaching) { Text("Teaching recordings") } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.cached) Text("Saved task history · connect to refresh")
                Text("All tasks", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(search, { search = it }, label = { Text("Search tasks") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TaskFilter.entries.forEach { choice ->
                        androidx.compose.material3.FilterChip(selected = filter == choice, onClick = { filter = choice }, label = { Text(choice.name) })
                    }
                }
            }
        }
        val recent = taskHistory(s.tasks, filter, search)

        if (recent.isNotEmpty()) {
            item { SectionTitle("Your tasks") }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Fo.Surface).border(1.dp, Fo.Border, RoundedCornerShape(18.dp))) {
                    recent.forEachIndexed { i, t -> if (i > 0) HorizontalDivider(color = Fo.Border); TaskRow(t) { onOpen(t) } }
                }
            }
        }
    }
}

/** State the goal; LensQi writes and safety-checks the plan. Manual photos and a site asset ground it. */
@Composable
fun NewTaskScreen(vm: TaskListViewModel, fixtures: List<Fixture>, initialMode: TaskMode, initialGoal: String, sourceId: String, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // What was typed and chosen survives a rotation or a process death: a goal and its details are not retyped.
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var goal by rememberSaveable { mutableStateOf(initialGoal) }
    var equipment by rememberSaveable { mutableStateOf("") }
    var details by rememberSaveable { mutableStateOf("") }
    var source by remember(sourceId) { mutableStateOf<com.lensqi.fieldops.data.api.TaskView?>(null) }
    val strings = listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<String>, String>(save = { it.toList() }, restore = { it.toMutableStateList() })
    val selectedPhotos = rememberSaveable(sourceId, saver = strings) { mutableStateListOf<String>() }
    val selectedAnswers = rememberSaveable(sourceId, saver = strings) { mutableStateListOf<String>() }
    LaunchedEffect(sourceId) {
        if (sourceId.isNotBlank()) {
            source = vm.loadSource(sourceId)
            if (details.isBlank()) details = source?.details.orEmpty()
        }
    }
    var asset by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    // The asset label scanner (codes and printed ids, read on the phone); a picked asset fills the site-record choice.
    var scanning by rememberSaveable { mutableStateOf(false) }
    if (scanning) com.lensqi.fieldops.ui.home.AssetScannerSheet(fixtures, onPicked = { picked -> asset = picked.fixtureId to picked.assetId; scanning = false }, onDismiss = { scanning = false })
    // The copies live in the cache; a restored path whose file is gone is dropped rather than sent empty.
    val manuals = rememberSaveable(saver = listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<File>, String>(save = { it.map { f -> f.path } }, restore = { it.map(::File).filter { f -> f.isFile }.toMutableStateList() })) { mutableStateListOf<File>() }
    val voice = rememberVoiceInput { goal = it }
    val scope = rememberCoroutineScope()
    var pickError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.clearError() }
    // The manual copies were made for this one create; once the server has the task they are leftovers in the cache.
    val created: (String) -> Unit = { id -> manuals.forEach { it.delete() }; manuals.clear(); onCreated(id) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        // Copy and convert off the main thread: any image the phone can open becomes an upright JPEG the server accepts.
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = File(context.cacheDir, "manual-raw-${System.currentTimeMillis()}")
                    context.contentResolver.openInputStream(uri)?.use { input -> raw.outputStream().use { input.copyTo(it) } }
                    val jpeg = downscaledJpeg(raw, 2400).also { raw.delete() } ?: return@runCatching null
                    File(context.cacheDir, "manual-${System.currentTimeMillis()}.jpg").apply { writeBytes(jpeg) }
                }.getOrNull()
            }
            if (f == null) pickError = "That image couldn't be read. Try a JPEG or PNG photo." else { pickError = null; if (manuals.size + selectedPhotos.size < 3) manuals.add(f) }
        }
    }
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = onBack) { Text("Back", color = Fo.Muted) }; Text("New task", style = MaterialTheme.typography.titleLarge) } }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Fo.Surface).border(1.dp, Fo.Border, RoundedCornerShape(16.dp)).padding(4.dp)) {
                listOf(TaskMode.Diy to "Do it yourself", TaskMode.Technician to "Technician").forEach { (m, label) ->
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (mode == m) Fo.Primary else Color.Transparent).clickable { mode = m }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(label, color = if (mode == m) Fo.OnPrimary else Fo.Muted, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                OutlinedTextField(if (voice.listening) voice.heard.ifEmpty { goal } else goal, { goal = it }, Modifier.weight(1f), label = { Text("What do you want to do?") }, minLines = 3)
                if (voice.supported) MicButton(voice.listening, { if (voice.listening) voice.stop() else voice.start() })
            }
        }
        item { Text(speechNotice(false), style = MaterialTheme.typography.bodySmall) }
        source?.let { session -> item {
            Column {
                Text("Carry forward from Just ask")
                session.answers.forEach { a ->
                    TextButton(onClick = {
                        if (!selectedAnswers.remove(a.at) && selectedAnswers.size < 10) selectedAnswers.add(a.at)
                    }) { Text((if (a.at in selectedAnswers) "✓ " else "") + a.question + " · " + a.answer) }
                }
                session.photos.forEach { p ->
                    coil.compose.AsyncImage(vm.photoUrl(session.taskId, p.evidenceId), "Session photo " + p.evidenceId,
                        Modifier.fillMaxWidth().height(110.dp))
                    TextButton(onClick = {
                        if (!selectedPhotos.remove(p.evidenceId) && selectedPhotos.size + manuals.size < 3) selectedPhotos.add(p.evidenceId)
                    }) { Text((if (p.evidenceId in selectedPhotos) "✓ " else "") + "Photo " + p.evidenceId) }
                }
            }
        } }
        item { OutlinedTextField(equipment, { equipment = it.take(180) }, Modifier.fillMaxWidth(),
            label = { Text("Make, model and variant") }, placeholder = { Text("Check the equipment label if unsure") }) }
        item { OutlinedTextField(details, { details = it }, Modifier.fillMaxWidth(), label = { Text("Equipment model, variant and job details") }, placeholder = { Text(if (mode == TaskMode.Diy) "Engine size, tools you have" else "Site notes, access") }, minLines = 2) }
        if (mode == TaskMode.Technician && fixtures.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionTitle("Equipment on a site record (optional)") }
                    // The label on the equipment names the asset: scan it instead of finding it in the list.
                    TextButton(onClick = { scanning = true }) { Text("Scan the label", color = Fo.Primary) }
                }
            }
            items(fixtures.flatMap { f -> f.assets.map { f.fixtureId to it } }) { (fixtureId, a) ->
                val chosen = asset == fixtureId to a.assetId
                Panel(Modifier.clickable { asset = if (chosen) null else fixtureId to a.assetId }, border = if (chosen) Fo.Primary else Fo.Border) {
                    Text(a.displayName, fontWeight = FontWeight.Medium); Text("$fixtureId · ${a.assetId}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Manual photos (optional, up to 3)", fontWeight = FontWeight.Medium)
                manuals.forEachIndexed { i, f -> Row(verticalAlignment = Alignment.CenterVertically) { Text(f.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)); TextButton(onClick = { manuals.removeAt(i) }) { Text("Remove") } } }
                if (manuals.size + selectedPhotos.size < 3) TextButton(onClick = { pick.launch("image/*") }) { Text("Add a manual page or label") }
                pickError?.let { Text(it, color = Fo.Warning, fontSize = 12.sp) }
                Text("A photo of the owner's manual or product label lets LensQi use its exact specs.", style = MaterialTheme.typography.bodySmall)
            }
        }
        s.error?.let { item { Text(it, color = Fo.Failure, style = MaterialTheme.typography.bodySmall) } }
        item {
            PrimaryButton(if (s.creating) "Writing your plan…" else "Plan it", { val selected = siteForMode(mode, asset); vm.create(mode, goal, listOf(equipment.trim(), details.trim()).filter { it.isNotEmpty() }.joinToString("\n"), selected?.first, selected?.second, manuals.toList(), sourceId.takeIf { it.isNotBlank() }, selectedPhotos.toList(), selectedAnswers.toList(), created) },
                Modifier.fillMaxWidth(), enabled = !s.creating && goal.trim().length >= 3, icon = Icons.Outlined.AutoAwesome, busy = s.creating)
        }
        if (s.creating) item { Text("This takes up to half a minute. The plan is checked for safety before you see it.", style = MaterialTheme.typography.bodySmall) }
        item { Box(Modifier.height(24.dp)) }
    }
}

@Composable
private fun StepRow(step: TaskStep, n: Int, vm: TaskViewModel) {
    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).background(when (step.status) { StepStatus.Done -> Fo.Success.copy(alpha = .2f); StepStatus.Active -> Fo.Primary; else -> Fo.Surface }, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (step.status == StepStatus.Active) Fo.OnPrimary else Fo.Foreground)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(step.title + if (step.handsOn) " · hands-on" else "", fontWeight = FontWeight.Medium)
            // The plan's own words: an instruction about "the Delta cartridge" is not vocabulary to translate.
            Text(step.instruction, style = MaterialTheme.typography.bodySmall)
            TaskSources(step, vm)
            step.cautions.forEach { Text("${it.kind} · ${it.text}", color = Fo.Warning, fontSize = 12.sp) }
            if (step.stopIf.isNotEmpty()) Text("Stop if ${step.stopIf.joinToString("; ")}.", color = Fo.Failure, fontSize = 12.sp)
        }
    }
}

/** The AI-written plan, its risk and safety notes, before anything starts. */
@Composable
fun PlanScreen(vm: TaskViewModel, onBack: () -> Unit, onStep: () -> Unit, onDone: () -> Unit, onTeaching: () -> Unit = {}) {
    val s by vm.state.collectAsStateWithLifecycle()
    // Step statuses change in the step runner; reload when coming back.
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }
    val t = s.displayed
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = onBack) { Text("Back", color = Fo.Muted) }; Text(if (t?.mode == TaskMode.Technician) "Job plan" else "Your plan", style = MaterialTheme.typography.titleLarge) } }
        if (t == null) { item { TaskLoading(s.error, Fo.Failure) { vm.clearError(); vm.refresh() } }; return@LazyColumn }
        val plan = t.plan
        item { WorkPackageButton(vm) }
        if (plan == null) {
            item {
                AiCard(t.goal, "The plan isn't ready", "The planner's answer failed its checks or the service was busy. A new attempt is checked the same way.") {
                    PrimaryButton(if (s.busy == "plan") "Writing the plan…" else "Try again", { vm.replan() }, Modifier.fillMaxWidth(), busy = s.busy == "plan")
                }
            }
            item { StopTask(t.phase, s.busy == "stop") { vm.abandon(onBack) } }
            return@LazyColumn
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(plan.sourceNote.uppercase(), fontSize = 10.sp, color = Fo.Muted, letterSpacing = 1.sp)
                Text(plan.title, style = MaterialTheme.typography.titleMedium)
                Text(plan.summary, style = MaterialTheme.typography.bodySmall)
                Text("${plan.risk} risk · about ${plan.estimatedMinutes} min · ${plan.steps.size} steps", color = riskColor(plan.risk), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (plan.professionalRequired) item {
            Panel(border = Fo.Failure, background = Fo.Failure.copy(alpha = .1f)) {
                Text("This needs a professional", color = Fo.Failure, fontWeight = FontWeight.SemiBold)
                Text("${plan.professionalReason.orEmpty()} LensQi will only help you prepare and document; it won't guide hands-on work here.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (plan.tools.isNotEmpty() || plan.parts.isNotEmpty()) item {
            Panel {
                if (plan.tools.isNotEmpty()) Text("Tools: ${plan.tools.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                if (plan.parts.isNotEmpty()) Text("Parts: ${plan.parts.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Fo.Surface).border(1.dp, Fo.Border, RoundedCornerShape(18.dp))) {
                plan.steps.forEachIndexed { i, step -> if (i > 0) HorizontalDivider(color = Fo.Border); StepRow(step, i + 1, vm) }
            }
        }
        if (s.cachedAt != null) item { Text("Saved plan · ${s.cachedAt}") }
        if (s.pendingSteps.isNotEmpty()) item { Text("${s.pendingSteps.size} saved step updates") }
        if (s.queuedChecks > 0) item {
            Panel(border = Fo.Warning) {
                Text("${s.queuedChecks} check photo${if (s.queuedChecks == 1) "" else "s"} saved on this phone, waiting for the network", style = MaterialTheme.typography.bodySmall)
                Text("The verdict is read out and shown on the step when it arrives.", style = MaterialTheme.typography.bodySmall, color = Fo.Muted)
            }
        }
        s.error?.let { item { Text(it, color = Fo.Failure, style = MaterialTheme.typography.bodySmall) } }
        item {
            if (t.phase == TaskPhase.Abandoned) Panel { Text("You stopped this task. Its record is kept.", style = MaterialTheme.typography.bodySmall) }
            else if (t.phase != TaskPhase.Planned) PrimaryButton(if (t.phase == TaskPhase.InProgress) "Continue" else "See the summary", { if (t.phase == TaskPhase.InProgress) onStep() else onDone() }, Modifier.fillMaxWidth())
            else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val cautions = plan.steps.flatMap { it.cautions }.map { it.text }.distinct()
                Panel(border = if (plan.needsSafetyAck) Fo.Warning else Fo.Border) {
                    if (plan.needsSafetyAck) Text("Safety notes", fontWeight = FontWeight.SemiBold, color = Fo.Warning)
                    if (cautions.isNotEmpty()) Text(cautions.take(4).joinToString(" · ") + if (cautions.size > 4) " · …" else "", style = MaterialTheme.typography.bodySmall)
                    Text("This plan is AI-written, not a safety certification. Check specs in your manual and stop if anything looks different.", style = MaterialTheme.typography.bodySmall)
                }
                // Starting is the acknowledgement: the notes sit right above the button that says so.
                PrimaryButton(if (plan.needsSafetyAck) "I've read the safety notes · Start" else "Start step 1", { vm.accept(plan.needsSafetyAck, onStep) }, Modifier.fillMaxWidth(), busy = s.busy == "accept")
            }
        }
        item { TextButton(onClick = onTeaching) { Text("Record a demonstration of this task") } }
        item { StopTask(t.phase, s.busy == "stop") { vm.abandon(onBack) } }
        item { Text("Plan by ${plan.modelId ?: "AI"} · AI inference", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 24.dp)) }
    }
}

/**
 * One step at a time on the live camera (CameraX, or ARCore where the phone has it): outlines of the step's parts, the
 * instruction spoken aloud, push-to-talk questions and commands, a full-resolution photo check, and Done.
 */
@Composable
fun StepScreen(vm: TaskViewModel, onClose: () -> Unit, onDone: () -> Unit, onGlasses: () -> Unit = {}) {
    val s by vm.state.collectAsStateWithLifecycle()
    val t = s.displayed
    val current = t?.currentStep
    val steps = t?.plan?.steps.orEmpty()
    // Back shows an earlier step read-only; it never restarts a finished step (the server refuses that).
    var viewingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(current?.id) { viewingId = null }
    val step = steps.firstOrNull { it.id == viewingId } ?: current
    val reviewing = step != null && current != null && step.id != current.id
    // The server coaches and answers for its current step, so while an earlier step is on screen questions and live
    // assist are off, rather than show or say advice about a different step than the one shown. Repeat reads what is shown.
    val reviewingNow by rememberUpdatedState(reviewing)
    val shown by rememberUpdatedState(step)
    val n = steps.indexOfFirst { it.id == step?.id }
    // Not state: the camera hands this over on every composition, and voice commands only read it.
    val camera = remember { HandleRef() }
    LaunchedEffect(t?.phase, s.pendingCompletion) { if (t?.phase == TaskPhase.Completed || s.pendingCompletion) onDone() }
    LaunchedEffect(step?.id) {
        vm.clearAnswer()
        step?.let { vm.speaker.speak(stepSpeech(it, n + 1), force = true, replace = true) }
    }
    var typed by remember { mutableStateOf("") }
    var muted by remember { mutableStateOf(!vm.speaker.enabled) }
    val pace = rememberVoicePace(vm.speaker, camera) { !reviewingNow }
    val voice = rememberVoiceInput(vm.speaker) { said ->
        val now = vm.state.value
        val current = now.displayed?.currentStep ?: return@rememberVoiceInput
        val idle = now.busy == null
        val command = parseCommand(said)
        // While paused nothing said is a command or a question; only "resume" (and the voice switch) is heard, so ambient
        // speech never reaches the server. Hands-free keeps listening for it, or a pause by voice could only be ended by a tap.
        if (!HandsFreePolicy.pausedCommandAllowed(command, pace.paused)) return@rememberVoiceInput
        // A command that can't run now says why; one that runs is heard through the next step being read.
        when (command) {
            VoiceCommand.Next -> if (viewingId != null) viewingId = null else if (idle) vm.complete(current.id) {} else vm.speaker.speak(STILL_WORKING, force = true)
            VoiceCommand.Skip -> if (viewingId != null) vm.speaker.speak("Go to the current step to skip it. Say next to go there.", force = true)
                else if (idle) vm.skip(current.id) {} else vm.speaker.speak(STILL_WORKING, force = true)
            VoiceCommand.Back -> steps.getOrNull(n - 1)?.let { viewingId = it.id } ?: vm.speaker.speak("This is the first step.", force = true)
            // A check is recorded against the server's current step, never against a finished one being looked back at.
            VoiceCommand.Check -> if (reviewingNow) vm.speaker.speak("Go to the current step to check it. Say next to go there.", force = true) else camera.value?.capture?.invoke()
            VoiceCommand.Repeat -> (shown ?: current).let { vm.speaker.speak(stepSpeech(it), force = true, replace = true) }
            VoiceCommand.Mute -> { vm.speaker.mute(); muted = true }
            VoiceCommand.Unmute -> { vm.speaker.unmute(); muted = false }
            VoiceCommand.Pause -> pace.pause(camera.value)
            VoiceCommand.Resume -> pace.resume(camera.value)
            null -> if (reviewingNow) vm.speaker.speak("Questions are about the current step. Say next to go to it.", force = true)
                else vm.ask(said, camera.value?.latestFrame?.invoke())
        }
    }
    // Hands-free holds while a request is pending (the question being answered, a step moving). It listens through a pause,
    // so "resume" is heard, and on an earlier step, so "next" brings the technician back; the handler above takes no questions there.
    val handsFree = rememberHandsFree(voice, vm.speaker, busy = s.busy != null, paused = pace.paused)
    // Silence between questions is the normal state of hands-free, not something to say or show.
    val voiceError = voice.error?.takeUnless { handsFree.on && it == NOTHING_HEARD }
    SpokenErrors(vm.speaker, s.error ?: s.notice, voiceError)
    if (t == null || step == null) { TaskLoading(if (t == null) s.error else null, FoDark.Failure) { vm.clearError(); vm.refresh() }; return }
    val needsCheck = step.check?.required == true && step.lastCheck == null
    // An access step (a ladder or stool to reach a part): the phone measures the part's height against the person's reach.
    var measured by remember { mutableStateOf(emptyList<com.lensqi.fieldops.domain.live.LiveObject>()) }
    val reach = if (reviewing) null else reachAdvice(step.accessFor, measured, t.person?.standingReachCm)
    CameraScreen(
        prompt = step.instruction,
        analyzeLive = vm::analyzeLiveFrame,
        targetConcept = step.accessFor ?: step.lookFor.firstOrNull(),
        liveAllowed = !reviewing,
        // The current step, not the one being reviewed: looking back at an earlier step keeps the confirmed part.
        scopeKey = current?.id ?: step.id,
        targetMemory = vm.spatialTarget,
        detailsSummary = step.stopIf.firstOrNull()?.let { "Stop if $it" } ?: step.cautions.firstOrNull()?.text,
        showDetails = s.cachedAt != null || s.pendingSteps.isNotEmpty() || step.referencePhotoIds.isNotEmpty() || step.sourceFacts.isNotEmpty() || step.cautions.isNotEmpty() || step.stopIf.isNotEmpty() || s.answer != null || s.error != null || s.notice != null || voiceError != null || voice.listening || voice.heard.isNotEmpty() || pace.paused || s.queuedChecks > 0 || s.refusedChecks.isNotEmpty() || reach?.canSkip == true || handsFree.on,
        onObjects = { measured = it; vm.measured = it },
        onClose = onClose,
        onCaptured = { file -> vm.check(step.id, file) },
        header = (if (reviewing) "Earlier step ${n + 1} of ${steps.size} · " else "Step ${n + 1} of ${steps.size} · ") + step.title,
        onGuidance = pace::guidance,
        // A tapped outline becomes a question about that part, with the live frame, as the web app asks it; the server
        // answers it and the answer is spoken through the same path as a spoken question's.
        onAskPart = { part ->
            when {
                reviewingNow -> vm.speaker.speak("Questions are about the current step. Say next to go to it.", force = true)
                vm.state.value.busy != null -> vm.speaker.speak(STILL_WORKING, force = true)
                else -> {
                    // A question asked by tapping a box while the microphone is open ends the listen first, so the answer is spoken.
                    if (voice.listening) voice.abort()
                    vm.ask("Tell me about the ${part.label.lowercase()} in view. Is anything wrong with it for this step?", camera.value?.latestFrame?.invoke())
                }
            }
        },
        details = {
            if (pace.paused) PausedNote { pace.resume(camera.value) }
            reach?.let { r -> ReachNote(r) { vm.skipNotNeeded(step.id, r) } }
            if (s.cachedAt != null) Text("Saved plan · ${s.cachedAt}")
            s.pendingSteps.filter { it.state == CommandState.Pending }.forEach { Text("Step ${it.stepId}: update waiting for sync") }
            if (s.queuedChecks > 0) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("${s.queuedChecks} check photo${if (s.queuedChecks == 1) "" else "s"} saved on the phone, waiting for the network", color = FoDark.Foreground, fontSize = 12.sp)
                // A waiting photo can be withdrawn: a wrong shot, or one the service keeps failing on, need not hold the step.
                s.pendingChecks.forEach { command -> TextButton(onClick = { vm.withdrawCheck(command) }, contentPadding = PaddingValues(0.dp)) { Text("Remove the saved photo for step ${steps.indexOfFirst { it.id == command.stepId } + 1}", color = FoDark.Primary, fontSize = 12.sp) } }
            }
            s.refusedChecks.forEach { command ->
                Text("A saved check was refused: ${command.errorMessage.orEmpty()}", color = FoDark.Warning)
                Row {
                    TextButton(onClick = { vm.retryCheck(command) }) { Text(if (command.serverBase == null) "Retry on this server" else "Retry saved photo") }
                    TextButton(onClick = { vm.removeCheck(command) }) { Text("Remove saved photo") }
                }
            }
            StepDetails(vm, t, step, s.answer, voice.listening, voice.heard, s.error ?: voiceError, if (voice.settingsNeeded) voice.openSettings else null, notice = s.notice)
        },
        controls = { handle ->
            camera.value = handle
            // Beside the microphone, not in the details: a tap on the picture can hide those, and the notice must be seen before the next mic tap.
            if (voice.supported && (handsFree.on || !handsFree.onDevice)) SpeechBadge(handsFree.onDevice)
            QuestionRow(typed, { typed = it }, voice.supported, voice.listening, s.busy == "ask", true, if (reviewing) "Go to the current step to ask" else "Ask about this step…", typingEnabled = !reviewing,
                // LensQi stops talking while it listens, so it never hears itself as the question; the voice input's hold
                // does that and reads a cut-off step again afterwards. With hands-free on, the mic button is its off switch.
                onMic = { if (handsFree.on) handsFree.toggle() else if (voice.listening) voice.stop() else voice.start() },
                onSend = { if (voice.listening) voice.abort(); vm.ask(typed, handle.latestFrame()); typed = "" })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StepButton(Icons.AutoMirrored.Outlined.ArrowBack, "Previous step", enabled = n > 0 && s.busy == null) { steps.getOrNull(n - 1)?.let { viewingId = it.id } }
                StepButton(Icons.Outlined.Visibility, "Use the glasses for this step", enabled = !reviewing && s.busy == null) { onGlasses() }
                StepButton(Icons.Outlined.CameraAlt, "Check this step with a photo", enabled = !reviewing && !handle.capturing && s.busy == null, highlight = needsCheck, busy = handle.capturing || s.busy == "check") { handle.capture() }
                Box(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(16.dp)).background(if (s.busy != null) FoDark.Primary.copy(alpha = .4f) else FoDark.Primary)
                    .clickable(enabled = s.busy == null) { if (reviewing) viewingId = null else vm.complete(step.id) {} }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (s.busy == "move") CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = FoDark.Background) else Icon(Icons.Outlined.Check, null, tint = FoDark.Background)
                        Text(if (reviewing) "Current step" else "Done", color = FoDark.Background, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MuteButton(muted) { if (muted) vm.speaker.unmute() else vm.speaker.mute(); muted = !muted }
                if (voice.supported) HandsFreeChip(handsFree.on, voice.listening, handsFree.toggle)
                Text(when {
                    needsCheck -> "Tip: a quick check photo can confirm this step."
                    handsFree.on -> "Hands-free: the microphone opens again after each answer."
                    voice.supported -> "Say “next”, “repeat”, “check this”, or ask a question. Hands-free keeps listening."
                    else -> ""
                }, color = FoDark.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                if (!reviewing) TextButton(onClick = { vm.skip(step.id) {} }, enabled = s.busy == null) { Text("Skip", color = FoDark.Muted, fontSize = 12.sp) }
            }
        },
    )
}

/**
 * A task that has not loaded: a spinner while it loads, and when it could not (the server out of reach, say) the reason
 * and a retry, rather than a spinner that never ends.
 */
@Composable
private fun TaskLoading(error: String?, errorColor: Color, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (error == null) CircularProgressIndicator()
        else {
            Text(error, color = errorColor, textAlign = TextAlign.Center)
            PrimaryButton("Try again", onRetry, Modifier.fillMaxWidth())
        }
    }
}

/** Whether the person needs this ladder or stool step: measured height against estimated reach, with a skip they choose. */
@Composable
private fun ReachNote(advice: ReachAdvice, onSkip: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .62f)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(advice.text, color = FoDark.Foreground, fontSize = 13.sp, lineHeight = 18.sp)
        if (advice.canSkip) TextButton(onClick = onSkip, contentPadding = PaddingValues(0.dp)) { Text("Skip this step: not needed", color = FoDark.Primary, fontWeight = FontWeight.SemiBold) }
    }
}

/** Stopping a task that is not finished, after a confirmation. */
@Composable
private fun StopTask(phase: TaskPhase, busy: Boolean, onStop: () -> Unit) {
    if (phase != TaskPhase.Planned && phase != TaskPhase.InProgress) return
    var confirming by remember { mutableStateOf(false) }
    TextButton(onClick = { confirming = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Stopping…" else "Stop this task", color = Fo.Muted) }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("Stop this task?") },
        text = { Text("Its record stays in your list, but no more steps can be done.") },
        confirmButton = { TextButton(onClick = { confirming = false; onStop() }) { Text("Stop task", color = Fo.Failure) } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Keep going") } },
    )
}

private class HandleRef { var value: CameraHandle? = null }

private const val STILL_WORKING = "One moment, LensQi is still working on the last request."

/** What is read aloud for a step: the instruction, then each caution and stop condition the screen shows. */
internal fun stepSpeech(step: TaskStep, number: Int? = null): String {
    fun sentence(s: String) = s.trim().let { if (it.isEmpty() || it.last() in ".!?") it else "$it." }
    return (listOfNotNull(number?.let { "Step $it" }, step.title, step.instruction) + step.cautions.map { it.text } + step.stopIf.map { "Stop if $it" })
        .map(::sentence).filter { it.isNotEmpty() }.joinToString(" ")
}

/** Errors are read aloud, so a hands-free user hears why a command didn't work. */
@Composable
private fun SpokenErrors(speaker: SpeechOutput, error: String?, voiceError: String?) {
    LaunchedEffect(error) { error?.let { speaker.speak(it, force = true) } }
    LaunchedEffect(voiceError) { voiceError?.let { speaker.speak(it, force = true) } }
}

/**
 * Spoken live guidance and the pause command for one camera screen. Guidance goes through [SpokenFeedbackGate], so
 * unchanged advice is not repeated and lines are spaced; "pause" holds speech and live coaching until "resume". A hint
 * waits while the speaker is busy (a step, answer or check result being read, or the microphone listening).
 */
internal class VoicePace(private val speaker: SpeechOutput) {
    var paused by mutableStateOf(false)
        private set
    private var wasLive = false
    private var gate = SpokenFeedbackGate()
    private var latest = ""
    private var waited = false

    /**
     * Losing the target cuts off a part-specific hint still being spoken and drops the gate's spacing, so the
     * recovery advice follows at once instead of up to eight seconds later. A step, answer or check result is never cut off.
     */
    fun guidance(text: String) {
        if (text.startsWith(TechnicianText.LOST_ADVICE) && !latest.startsWith(TechnicianText.LOST_ADVICE)) { if (!speaker.busy) speaker.stop(); gate = SpokenFeedbackGate() }
        latest = text
    }

    /** Called on a short timer; speaks the latest guidance when the gate allows it. */
    fun tick(now: Long, allowed: Boolean) {
        if (paused || !allowed) return
        // Busy: the gate isn't asked, so the hint stays pending and plays 1.5 s after the speaker is free, however
        // long it had settled before.
        if (speaker.busy) { waited = true; return }
        if (waited) { gate.restartSettle(); waited = false }
        gate.next(latest, now)?.let { speaker.speak(it) }
    }

    fun pause(camera: CameraHandle?) {
        if (paused) return
        wasLive = camera?.live == true
        camera?.setLive?.invoke(false)
        latest = ""
        speaker.held = true
        paused = true
    }

    fun resume(camera: CameraHandle?) {
        if (!paused) return
        paused = false
        speaker.held = false
        if (wasLive) camera?.setLive?.invoke(true)
    }
}

@Composable
private fun rememberVoicePace(speaker: SpeechOutput, camera: HandleRef, allowed: () -> Boolean): VoicePace {
    val pace = remember { VoicePace(speaker) }
    LaunchedEffect(pace) {
        while (true) {
            delay(500)
            pace.tick(SystemClock.elapsedRealtime(), allowed() && camera.value?.live == true)
        }
    }
    // Leaving the screen never leaves the app's voice held.
    DisposableEffect(pace) { onDispose { speaker.held = false } }
    return pace
}

@Composable
private fun PausedNote(onResume: () -> Unit) =
    Text("Paused: no speech or live coaching. Say “resume” or tap here.", color = FoDark.Primary, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .55f)).clickable(onClick = onResume).padding(horizontal = 12.dp, vertical = 8.dp))

/** Ask by voice or by typing: both are always offered, so a missing microphone or a noisy room never blocks a question. */
@Composable
private fun QuestionRow(typed: String, onTyped: (String) -> Unit, voiceSupported: Boolean, listening: Boolean, asking: Boolean, enabled: Boolean, hint: String,
                        onMic: () -> Unit, onSend: () -> Unit, typingEnabled: Boolean = enabled) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (voiceSupported) Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(if (listening) FoDark.Primary else Color.Black.copy(alpha = .4f))
            .border(1.dp, FoDark.Border, RoundedCornerShape(16.dp)).clickable(enabled = enabled && !asking, onClick = onMic), contentAlignment = Alignment.Center) {
            Icon(if (listening) Icons.Outlined.Stop else Icons.Outlined.Mic, if (listening) "Stop listening" else "Ask or say a command", tint = FoDark.Foreground)
        }
        OutlinedTextField(typed, onTyped, Modifier.weight(1f), enabled = typingEnabled && !asking, singleLine = true,
            placeholder = { Text(if (asking) "Thinking…" else hint) },
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = FoDark.Foreground, unfocusedTextColor = FoDark.Foreground, disabledTextColor = FoDark.Muted,
                focusedPlaceholderColor = FoDark.Muted, unfocusedPlaceholderColor = FoDark.Muted, disabledPlaceholderColor = FoDark.Muted,
                focusedBorderColor = FoDark.Primary, unfocusedBorderColor = FoDark.Border, disabledBorderColor = FoDark.Border, cursorColor = FoDark.Primary,
                focusedContainerColor = Color.Black.copy(alpha = .4f), unfocusedContainerColor = Color.Black.copy(alpha = .4f), disabledContainerColor = Color.Black.copy(alpha = .4f)))
        if (asking || typed.isNotBlank()) Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = .4f)).border(1.dp, FoDark.Border, RoundedCornerShape(16.dp))
            .clickable(enabled = typingEnabled && !asking && typed.isNotBlank(), onClick = onSend), contentAlignment = Alignment.Center) {
            if (asking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.AutoMirrored.Outlined.Send, "Ask", tint = FoDark.Foreground)
        }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, highlight: Boolean = false, busy: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = .4f)).border(1.dp, if (highlight) FoDark.Primary else FoDark.Border, RoundedCornerShape(16.dp))
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(icon, label, tint = if (enabled) (if (highlight) FoDark.Primary else FoDark.Foreground) else FoDark.Muted)
    }
}

@Composable
private fun ColumnScope.StepDetails(vm: TaskViewModel, task: com.lensqi.fieldops.data.api.TaskView, step: TaskStep, answer: Pair<String, String>?, listening: Boolean, heard: String, error: String?, onOpenSettings: (() -> Unit)? = null, notice: String? = null) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = .55f)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TaskSources(step, vm)
        if (step.cautions.isNotEmpty()) Text(step.cautions.joinToString("  ·  ") { "${it.kind}: ${it.text}" }, color = FoDark.Warning, fontSize = 12.sp)
        if (step.stopIf.isNotEmpty()) Text("Stop if ${step.stopIf.joinToString("; ")}.", color = FoDark.Failure, fontSize = 12.sp)
        // The model's reason and answers are shown as written: they name the equipment, which is not vocabulary to translate.
        step.lastCheck?.let { c ->
            Text("Camera check: ${verdictText(c.verdict)} · ${c.reason}" + checkPhotoNote(task, c), color = verdictColor(c.verdict), fontSize = 12.sp)
            Text("A suggestion. You decide when the step is done.", color = FoDark.Muted, fontSize = 10.sp)
        }
        answer?.let { (q, a) -> Text("You asked: $q", color = FoDark.Muted, fontSize = 11.sp, maxLines = 1); Text(a, color = FoDark.Foreground, fontSize = 13.sp) }
        if (listening) Text(heard.ifEmpty { "Listening…" }, color = FoDark.Primary, fontSize = 13.sp)
        error?.let { Text(it, color = FoDark.Warning, fontSize = 12.sp) }
        // Saved for later is a delay, not a failure: shown in the plain colour, not the warning one.
        notice?.let { Text(it, color = FoDark.Muted, fontSize = 12.sp) }
        onOpenSettings?.let { TextButton(onClick = it, contentPadding = PaddingValues(0.dp)) { Text("Open settings for the microphone", color = FoDark.Primary, fontSize = 12.sp) } }
    }
}

/** What was done, skipped and asked. Technician tasks on a site record continue to FieldProof. */
@Composable
fun TaskDoneScreen(vm: TaskViewModel, onHome: () -> Unit, onInspection: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    // Back from the FieldProof inspection: reload so the linked stage and outcome are current.
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }
    val t = s.displayed
    val plan = t?.plan
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = onHome) { Text("Home", color = Fo.Muted) }; Text("Summary", style = MaterialTheme.typography.titleLarge) } }
        if (t == null) { item { TaskLoading(s.error, Fo.Failure) { vm.clearError(); vm.refresh() } }; return@LazyColumn }
        // Check photos still saved on the phone stay manageable after the task closes; the task record itself is the server's.
        if (s.queuedChecks > 0 || s.refusedChecks.isNotEmpty()) item {
            Panel {
                Text("Check photos saved on this phone", fontWeight = FontWeight.Medium)
                if (s.queuedChecks > 0) Text("${s.queuedChecks} waiting for the network", style = MaterialTheme.typography.bodySmall)
                s.pendingChecks.forEach { command -> TextButton(onClick = { vm.withdrawCheck(command) }) { Text("Remove the waiting photo for step ${plan?.steps?.indexOfFirst { it.id == command.stepId }?.plus(1) ?: "?"}") } }
                s.refusedChecks.forEach { command ->
                    Text("Refused: ${com.lensqi.fieldops.ui.common.friendly(command.errorCode ?: "unknown", command.errorMessage.orEmpty())}", color = Fo.Warning, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.removeCheck(command) }) { Text("Remove saved photo") }
                }
            }
        }
        item { WorkPackageButton(vm) }
        // A failed export or FieldProof link is said here, under the button that asked for it; the next action clears it.
        s.error?.let { item { Text(it, color = Fo.Failure, style = MaterialTheme.typography.bodySmall) } }
        if (plan == null) {
            item { Panel {
                Text(t.title ?: t.goal)
                if (t.titleByAi) Text("Topic named by AI from the conversation", style = MaterialTheme.typography.bodySmall, color = Fo.Muted)
                Text(if (t.phase == TaskPhase.Abandoned) "Stopped · record retained" else if (t.phase == TaskPhase.Completed) "Session ended · record retained" else "No plan was recorded")
                Text("${t.photos.size} saved photos")
                t.answers.forEach { answer -> Text("You asked: ${answer.question}"); Text(answer.answer) }
            } }
            return@LazyColumn
        }
        val skipped = plan.steps.count { it.status == StepStatus.Skipped }
        item {
            AiCard(plan.title, if (t.phase == TaskPhase.Abandoned) "Stopped · record retained" else if (t.phase != TaskPhase.Completed) (if (s.pendingCompletion) "All steps marked done on this phone · waiting for server confirmation" else "In progress") else if (skipped > 0) "Done, with $skipped step${if (skipped == 1) "" else "s"} skipped" else "All steps done",
                if (t.mode == TaskMode.Diy) "LensQi guided this from an AI-written plan. Recheck anything safety-critical, such as leaks, levels and fastenings." else null,
                tone = if (skipped > 0) Fo.Warning else Fo.Success) {
                if (t.site != null) {
                    s.linked?.let { i ->
                        val verified = i.stage == InspectionStage.VerifiedComplete
                        Text("FieldProof: " + if (verified && i.outcomeKind == VerificationOutcomeKind.VerifiedCompleteSimulated) "Verified complete · simulated check" else stageLabel(i.stage),
                            color = if (verified) Fo.Success else if (i.stage == InspectionStage.VerificationFailed) Fo.Failure else Fo.Muted, fontWeight = FontWeight.Medium)
                    }
                    if (t.inspectionId != null) PrimaryButton(if (s.linked?.stage == InspectionStage.VerifiedComplete) "View inspection record" else "Open inspection", { onInspection(t.inspectionId) }, Modifier.fillMaxWidth())
                    // The server refuses to link a stopped task, so only a linked inspection is offered there.
                    else if (t.phase != TaskPhase.Abandoned) PrimaryButton("Verify with FieldProof", { vm.linkInspection(onInspection) }, Modifier.fillMaxWidth(), busy = s.busy == "link")
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Fo.Surface).border(1.dp, Fo.Border, RoundedCornerShape(18.dp))) {
                plan.steps.forEachIndexed { i, step ->
                    if (i > 0) HorizontalDivider(color = Fo.Border)
                    Column(Modifier.padding(14.dp)) {
                        Text("${i + 1}. ${step.title}", fontWeight = FontWeight.Medium, color = if (step.status == StepStatus.Done) Fo.Foreground else Fo.Muted)
                        step.lastCheck?.let { Text("Camera check: ${verdictText(it.verdict)} · ${it.reason}" + checkPhotoNote(t, it), color = verdictColor(it.verdict), fontSize = 12.sp) }
                    }
                }
            }
        }
        if (t.answers.isNotEmpty()) item {
            Panel { Text("Your questions", fontWeight = FontWeight.Medium); t.answers.forEach { a -> Text(a.question, style = MaterialTheme.typography.bodySmall); Text(a.answer, fontSize = 13.sp) } }
        }
        item { Box(Modifier.height(24.dp)) }
    }
}

private const val LOOK = "What am I looking at, and is anything wrong?"

/**
 * Free-form help: the live camera and a conversation, no plan. Questions by voice use the latest live frame; "Look" takes
 * a full-resolution photo and asks about it. A longer job can become a step-by-step plan.
 */
@Composable
fun FreeformScreen(vm: TaskViewModel, onClose: () -> Unit, onPlan: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val t = s.displayed
    val camera = remember { HandleRef() }
    val open = t?.phase == TaskPhase.InProgress
    val answers = t?.answers.orEmpty().asReversed()
    var typed by remember { mutableStateOf("") }
    var muted by remember { mutableStateOf(!vm.speaker.enabled) }
    val pace = rememberVoicePace(vm.speaker, camera) { true }
    val voice = rememberVoiceInput(vm.speaker) { said ->
        val command = parseCommand(said)
        // While paused nothing said is a command or a question; only "resume" (and the voice switch) is heard, so ambient
        // speech never reaches the server. Hands-free keeps listening for it.
        if (!HandsFreePolicy.pausedCommandAllowed(command, pace.paused)) return@rememberVoiceInput
        when (command) {
            VoiceCommand.Pause -> pace.pause(camera.value)
            VoiceCommand.Resume -> pace.resume(camera.value)
            VoiceCommand.Repeat -> answers.firstOrNull()?.let { vm.speaker.speak(it.answer, force = true, replace = true) } ?: vm.speaker.speak("There's no answer yet. Ask a question.", force = true)
            VoiceCommand.Mute -> { vm.speaker.mute(); muted = true }
            VoiceCommand.Unmute -> { vm.speaker.unmute(); muted = false }
            VoiceCommand.Check -> camera.value?.capture?.invoke()
            else -> vm.ask(said, camera.value?.latestFrame?.invoke())
        }
    }
    // Hands-free holds while a question is being answered and once the session has ended; it listens through a pause.
    val handsFree = rememberHandsFree(voice, vm.speaker, busy = s.busy != null || !open, paused = pace.paused)
    val voiceError = voice.error?.takeUnless { handsFree.on && it == NOTHING_HEARD }
    SpokenErrors(vm.speaker, s.error, voiceError)
    CameraScreen(
        prompt = answers.firstOrNull()?.answer ?: "Point at the equipment and ask a question.",
        analyzeLive = vm::analyzeLiveFrame,
        onClose = onClose,
        onCaptured = { file -> vm.askAboutPhoto(LOOK, file) },
        // A title the AI named from the conversation is labelled as such, as the plan and summary label it.
        header = t?.let { if (it.titleByAi) "${it.title ?: it.goal} · AI-named" else it.title } ?: "Just ask",
        onGuidance = pace::guidance,
        showDetails = handsFree.on || voiceError != null || pace.paused,
        // A tapped outline becomes a question about that part, with the live frame, in the web app's words.
        onAskPart = { part ->
            when {
                !open -> vm.speaker.speak("This session has ended.", force = true)
                vm.state.value.busy != null -> vm.speaker.speak(STILL_WORKING, force = true)
                else -> {
                    if (voice.listening) voice.abort()
                    vm.ask("Tell me about the ${part.label.lowercase()} in view. Is anything wrong with it?", camera.value?.latestFrame?.invoke())
                }
            }
        },
        details = {
            if (pace.paused) PausedNote { pace.resume(camera.value) }
            val note = s.error ?: voiceError
            if (answers.isNotEmpty() || voice.listening || note != null || !open) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = .55f)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                answers.firstOrNull()?.let { Text("You asked: ${it.question}", color = FoDark.Muted, fontSize = 11.sp, maxLines = 2) }
                if (voice.listening) Text(voice.heard.ifEmpty { "Listening…" }, color = FoDark.Primary, fontSize = 13.sp)
                note?.let { Text(it, color = FoDark.Warning, fontSize = 12.sp) }
                if (!open) Text("This session has ended.", color = FoDark.Muted, fontSize = 12.sp)
            }
        },
        controls = { handle ->
            camera.value = handle
            // Beside the microphone, not in the details: a tap on the picture can hide those, and the notice must be seen before the next mic tap.
            if (voice.supported && (handsFree.on || !handsFree.onDevice)) SpeechBadge(handsFree.onDevice)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    QuestionRow(typed, { typed = it }, voice.supported, voice.listening, s.busy == "ask", open, "Ask anything about what you see…",
                        onMic = { if (handsFree.on) handsFree.toggle() else if (voice.listening) voice.stop() else voice.start() },
                        onSend = { if (voice.listening) voice.abort(); vm.ask(typed, handle.latestFrame()); typed = "" })
                }
                StepButton(Icons.Outlined.CameraAlt, "Take a photo and ask what it shows", enabled = open && !handle.capturing && s.busy == null, busy = handle.capturing) { handle.capture() }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MuteButton(muted) { if (muted) vm.speaker.unmute() else vm.speaker.mute(); muted = !muted }
                if (voice.supported && open) HandsFreeChip(handsFree.on, voice.listening, handsFree.toggle)
                TextButton(onClick = { onPlan(t?.title?.takeUnless { it == "Just ask" } ?: answers.firstOrNull { it.question != LOOK }?.question.orEmpty()) }) { Text("Make a plan", color = FoDark.Primary, fontSize = 12.sp) }
                Text(if (handsFree.on) "Hands-free: the microphone opens again after each answer." else "Say “repeat”, “check this”, or ask anything.", color = FoDark.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                if (open) TextButton(onClick = { vm.finish(onClose) }, enabled = s.busy == null) { Text("End", color = FoDark.Muted, fontSize = 12.sp) }
            }
        },
    )
}

@Composable
private fun TaskSources(step: TaskStep, vm: TaskViewModel) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    step.referencePhotoIds.forEach { id ->
        Expandable("Reference page · $id") {
            // Whether the page is cached is read off the main thread, once per page, not on every recomposition.
            val remote = vm.library.server.trimEnd('/') + "/v1/tasks/${vm.taskId}/artifacts/$id"
            val model by produceState<Any>(remote, id) {
                val file = vm.library.reference(vm.taskId, id)
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { if (file.isFile) file else remote }
            }
            coil.compose.AsyncImage(model, "Supporting reference page", Modifier.fillMaxWidth().height(280.dp))
        }
    }
    if (step.sourceFacts.isNotEmpty()) Expandable("Sources · guidance, not evidence") {
        step.sourceFacts.forEach { fact ->
            Text("${fact.provenance} · ${fact.statement}", style = MaterialTheme.typography.bodySmall)
            Text("${fact.citation.documentTitle} · ${fact.citation.sourceLocator}", style = MaterialTheme.typography.bodySmall)
            fact.citation.sourceUrl?.takeIf { com.lensqi.fieldops.ui.receipt.webOrigin(it) != null }?.let { url ->
                TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text("Open source") }
            }
        }
    }
}

@Composable
private fun WorkPackageButton(vm: TaskViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    TextButton(enabled = !busy, onClick = {
        busy = true
        scope.launch {
            try {
                vm.exportPackage(File(context.cacheDir, "receipt-exports"))?.let { file ->
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        clipData = android.content.ClipData.newRawUri("Job package", uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(send, "Share work package"))
                }
            } finally { busy = false }
        }
    }) { Text(if (busy) "Preparing package…" else "Share work package") }
}
