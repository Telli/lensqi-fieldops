package com.lensqi.fieldops.ui.task

import com.lensqi.fieldops.wearables.SpokenFeedbackGate
import com.lensqi.fieldops.voice.SpeechOutput
import kotlinx.coroutines.delay
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
import com.lensqi.fieldops.voice.VoiceCommand
import com.lensqi.fieldops.voice.parseCommand
import com.lensqi.fieldops.voice.rememberVoiceInput
import java.io.File

fun verdictText(v: StepVerdict) = when (v) { StepVerdict.LooksDone -> "Looks done"; StepVerdict.NotYet -> "Not yet"; StepVerdict.CantTell -> "Can't tell" }
private fun verdictColor(v: StepVerdict) = when (v) { StepVerdict.LooksDone -> Fo.Success; StepVerdict.NotYet -> Fo.Warning; StepVerdict.CantTell -> Fo.Muted }
private fun riskColor(r: RiskLevel) = when (r) { RiskLevel.Low -> Fo.Success; RiskLevel.Moderate -> Fo.Warning; RiskLevel.High -> Fo.Failure }

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
            Text(if (t.mode == TaskMode.Freeform) "Just ask" else t.title ?: t.goal, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(if (t.mode == TaskMode.Freeform) "Free-form help · ${if (t.phase == TaskPhase.Completed) "ended" else "open"}" else when (t.phase) {
                TaskPhase.Completed -> "Done"; TaskPhase.Abandoned -> "Stopped"
                TaskPhase.Planned -> if (t.title != null) "Plan ready" else "No plan yet"
                TaskPhase.InProgress -> "${t.currentStepTitle ?: "In progress"} · ${t.finished}/${t.steps}"
            }, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = Fo.Muted)
    }
}

/** Two ways in: say what you want to do yourself, or go to technician work. */
@Composable
fun TaskHomeScreen(vm: TaskListViewModel, onPlan: (String) -> Unit, onDetails: (String) -> Unit, onJustAsk: () -> Unit, onTechnician: () -> Unit, onOpen: (TaskSummary) -> Unit, onProfile: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    var goal by remember { mutableStateOf("") }
    var planning by remember { mutableStateOf(false) }
    val writing = planning && s.creating
    val voice = rememberVoiceInput { goal = it }
    LaunchedEffect(Unit) { vm.refresh() }
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("What are we doing today?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onProfile) { Text("Profile", color = Fo.Muted) }
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
                        Text("Installs and repairs on site records, equipment checks and FieldProof receipts.", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = Fo.Muted)
                }
            }
        }
        val recent = s.tasks.filter { it.phase != TaskPhase.Abandoned }.take(6)
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
fun NewTaskScreen(vm: TaskListViewModel, fixtures: List<Fixture>, initialMode: TaskMode, initialGoal: String, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var mode by remember { mutableStateOf(initialMode) }
    var goal by remember { mutableStateOf(initialGoal) }
    var details by remember { mutableStateOf("") }
    var asset by remember { mutableStateOf<Pair<String, String>?>(null) }
    val manuals = remember { mutableStateListOf<File>() }
    val voice = rememberVoiceInput { goal = it }
    val scope = rememberCoroutineScope()
    var pickError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.clearError() }
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
            if (f == null) pickError = "That image couldn't be read. Try a JPEG or PNG photo." else { pickError = null; if (manuals.size < 3) manuals.add(f) }
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
        item { OutlinedTextField(details, { details = it }, Modifier.fillMaxWidth(), label = { Text("Details (optional)") }, placeholder = { Text(if (mode == TaskMode.Diy) "Engine size, tools you have" else "Site notes, access") }, minLines = 2) }
        if (mode == TaskMode.Technician && fixtures.isNotEmpty()) {
            item { SectionTitle("Equipment on a site record (optional)") }
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
                if (manuals.size < 3) TextButton(onClick = { pick.launch("image/*") }) { Text("Add a manual page or label") }
                pickError?.let { Text(it, color = Fo.Warning, fontSize = 12.sp) }
                Text("A photo of the owner's manual or product label lets LensQi use its exact specs.", style = MaterialTheme.typography.bodySmall)
            }
        }
        s.error?.let { item { Text(it, color = Fo.Failure, style = MaterialTheme.typography.bodySmall) } }
        item {
            PrimaryButton(if (s.creating) "Writing your plan…" else "Plan it", { vm.create(mode, goal, details, asset?.first, asset?.second, manuals.toList(), onCreated) },
                Modifier.fillMaxWidth(), enabled = !s.creating && goal.trim().length >= 3, icon = Icons.Outlined.AutoAwesome, busy = s.creating)
        }
        if (s.creating) item { Text("This takes up to half a minute. The plan is checked for safety before you see it.", style = MaterialTheme.typography.bodySmall) }
        item { Box(Modifier.height(24.dp)) }
    }
}

@Composable
private fun StepRow(step: TaskStep, n: Int) {
    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).background(when (step.status) { StepStatus.Done -> Fo.Success.copy(alpha = .2f); StepStatus.Active -> Fo.Primary; else -> Fo.Surface }, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (step.status == StepStatus.Active) Fo.OnPrimary else Fo.Foreground)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(step.title + if (step.handsOn) " · hands-on" else "", fontWeight = FontWeight.Medium)
            Text(step.instruction, style = MaterialTheme.typography.bodySmall)
            step.cautions.forEach { Text("${it.kind} · ${it.text}", color = Fo.Warning, fontSize = 12.sp) }
            if (step.stopIf.isNotEmpty()) Text("Stop if ${step.stopIf.joinToString("; ")}.", color = Fo.Failure, fontSize = 12.sp)
        }
    }
}

/** The AI-written plan, its risk and safety notes, before anything starts. */
@Composable
fun PlanScreen(vm: TaskViewModel, onBack: () -> Unit, onStep: () -> Unit, onDone: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    // Step statuses change in the step runner; reload when coming back.
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }
    val t = s.task
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = onBack) { Text("Back", color = Fo.Muted) }; Text(if (t?.mode == TaskMode.Technician) "Job plan" else "Your plan", style = MaterialTheme.typography.titleLarge) } }
        if (t == null) { item { TaskLoading(s.error, Fo.Failure) { vm.clearError(); vm.refresh() } }; return@LazyColumn }
        val plan = t.plan
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
                plan.steps.forEachIndexed { i, step -> if (i > 0) HorizontalDivider(color = Fo.Border); StepRow(step, i + 1) }
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
    val t = s.task
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
    LaunchedEffect(t?.phase) { if (t?.phase == TaskPhase.Completed) onDone() }
    LaunchedEffect(step?.id) {
        vm.clearAnswer()
        step?.let { vm.speaker.speak("Step ${n + 1}. ${it.title}. ${it.instruction} ${it.cautions.joinToString(". ") { c -> c.text }}", force = true) }
    }
    var typed by remember { mutableStateOf("") }
    val pace = rememberVoicePace(vm.speaker, camera) { !reviewingNow }
    val voice = rememberVoiceInput { said ->
        val now = vm.state.value
        val current = now.task?.currentStep ?: return@rememberVoiceInput
        val idle = now.busy == null
        when (parseCommand(said)) {
            VoiceCommand.Next -> if (viewingId != null) viewingId = null else if (idle) vm.complete(current.id) {}
            VoiceCommand.Skip -> if (idle && viewingId == null) vm.skip(current.id) {}
            VoiceCommand.Back -> steps.getOrNull(n - 1)?.let { viewingId = it.id }
            VoiceCommand.Check -> camera.value?.capture?.invoke()
            VoiceCommand.Repeat -> (shown ?: current).let { vm.speaker.speak("${it.title}. ${it.instruction}", force = true) }
            VoiceCommand.Mute -> { vm.speaker.enabled = false; vm.speaker.stop() }
            VoiceCommand.Unmute -> vm.speaker.enabled = true
            VoiceCommand.Pause -> pace.pause(camera.value)
            VoiceCommand.Resume -> pace.resume(camera.value)
            null -> if (reviewingNow) vm.speaker.speak("Questions are about the current step. Say next to go to it.", force = true)
                else vm.ask(said, camera.value?.latestFrame?.invoke())
        }
    }
    if (t == null || step == null) { TaskLoading(if (t == null) s.error else null, FoDark.Failure) { vm.clearError(); vm.refresh() }; return }
    val needsCheck = step.check?.required == true && step.lastCheck == null
    CameraScreen(
        prompt = step.instruction,
        analyzeLive = vm::analyzeLiveFrame,
        targetConcept = step.lookFor.firstOrNull(),
        liveAllowed = !reviewing,
        onClose = onClose,
        onCaptured = { file -> vm.check(step.id, file) },
        header = (if (reviewing) "Earlier step ${n + 1} of ${steps.size} · " else "Step ${n + 1} of ${steps.size} · ") + step.title,
        onGuidance = pace::guidance,
        details = {
            if (pace.paused) PausedNote { pace.resume(camera.value) }
            if (s.queuedChecks > 0) Text("${s.queuedChecks} check photo${if (s.queuedChecks == 1) "" else "s"} saved on the phone, waiting for the network", color = FoDark.Foreground, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 12.dp, vertical = 8.dp))
            StepDetails(step, s.answer, voice.listening, voice.heard, s.error ?: voice.error)
        },
        controls = { handle ->
            camera.value = handle
            QuestionRow(typed, { typed = it }, voice.supported, voice.listening, s.busy == "ask", true, if (reviewing) "Go to the current step to ask" else "Ask about this step…", typingEnabled = !reviewing,
                // LensQi stops talking while it listens, so it never hears itself as the question.
                onMic = { if (voice.listening) voice.stop() else { vm.speaker.stop(); voice.start() } },
                onSend = { vm.ask(typed, handle.latestFrame()); typed = "" })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StepButton(Icons.AutoMirrored.Outlined.ArrowBack, "Previous step", enabled = n > 0 && s.busy == null) { steps.getOrNull(n - 1)?.let { viewingId = it.id } }
                StepButton(Icons.Outlined.Visibility, "Use the glasses for this step", enabled = !reviewing && s.busy == null) { onGlasses() }
                StepButton(Icons.Outlined.CameraAlt, "Check this step with a photo", enabled = !handle.capturing && s.busy == null, highlight = needsCheck, busy = handle.capturing || s.busy == "check") { handle.capture() }
                Box(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(16.dp)).background(if (s.busy != null) FoDark.Primary.copy(alpha = .4f) else FoDark.Primary)
                    .clickable(enabled = s.busy == null) { if (reviewing) viewingId = null else vm.complete(step.id) {} }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (s.busy == "move") CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = FoDark.Background) else Icon(Icons.Outlined.Check, null, tint = FoDark.Background)
                        Text(if (reviewing) "Current step" else "Done", color = FoDark.Background, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (needsCheck) "Tip: a quick check photo can confirm this step." else if (voice.supported) "Say “next”, “repeat”, “check this”, or ask a question." else "", color = FoDark.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
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

/**
 * Spoken live guidance and the pause command for one camera screen. Guidance goes through [SpokenFeedbackGate], so
 * unchanged advice is not repeated and lines are spaced; "pause" holds speech and live coaching until "resume".
 */
private class VoicePace(private val speaker: SpeechOutput) {
    var paused by mutableStateOf(false)
        private set
    private var wasLive = false
    private val gate = SpokenFeedbackGate()
    private var latest = ""

    fun guidance(text: String) { latest = text }

    /** Called on a short timer; speaks the latest guidance when the gate allows it. */
    fun tick(now: Long, allowed: Boolean) {
        if (!paused && allowed) gate.next(latest, now)?.let { speaker.speak(it) }
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
private fun ColumnScope.StepDetails(step: TaskStep, answer: Pair<String, String>?, listening: Boolean, heard: String, error: String?) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = .55f)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (step.cautions.isNotEmpty()) Text(step.cautions.joinToString("  ·  ") { "${it.kind}: ${it.text}" }, color = FoDark.Warning, fontSize = 12.sp, maxLines = 3)
        if (step.stopIf.isNotEmpty()) Text("Stop if ${step.stopIf.joinToString("; ")}.", color = FoDark.Failure, fontSize = 12.sp, maxLines = 2)
        step.lastCheck?.let { c ->
            Text("AI check: ${verdictText(c.verdict)} · ${c.reason}", color = verdictColor(c.verdict), fontSize = 12.sp, maxLines = 3)
            Text("A suggestion. You decide when the step is done.", color = FoDark.Muted, fontSize = 10.sp)
        }
        answer?.let { (q, a) -> Text("You asked: $q", color = FoDark.Muted, fontSize = 11.sp, maxLines = 1); Text(a, color = FoDark.Foreground, fontSize = 13.sp, maxLines = 5) }
        if (listening) Text(heard.ifEmpty { "Listening…" }, color = FoDark.Primary, fontSize = 13.sp)
        error?.let { Text(it, color = FoDark.Warning, fontSize = 12.sp) }
    }
}

/** What was done, skipped and asked. Technician tasks on a site record continue to FieldProof. */
@Composable
fun TaskDoneScreen(vm: TaskViewModel, onHome: () -> Unit, onInspection: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    // Back from the FieldProof inspection: reload so the linked stage and outcome are current.
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }
    val t = s.task
    val plan = t?.plan
    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = onHome) { Text("Home", color = Fo.Muted) }; Text("Summary", style = MaterialTheme.typography.titleLarge) } }
        if (t == null || plan == null) { item { TaskLoading(if (t == null) s.error else null, Fo.Failure) { vm.clearError(); vm.refresh() } }; return@LazyColumn }
        val skipped = plan.steps.count { it.status == StepStatus.Skipped }
        item {
            AiCard(plan.title, if (t.phase != TaskPhase.Completed) "In progress" else if (skipped > 0) "Done, with $skipped step${if (skipped == 1) "" else "s"} skipped" else "All steps done",
                if (t.mode == TaskMode.Diy) "LensQi guided this from an AI-written plan. Recheck anything safety-critical, such as leaks, levels and fastenings." else null,
                tone = if (skipped > 0) Fo.Warning else Fo.Success) {
                if (t.site != null) {
                    s.linked?.let { i ->
                        val verified = i.stage == InspectionStage.VerifiedComplete
                        Text("FieldProof: " + if (verified && i.outcomeKind == VerificationOutcomeKind.VerifiedCompleteSimulated) "Verified complete · simulated check" else stageLabel(i.stage),
                            color = if (verified) Fo.Success else if (i.stage == InspectionStage.VerificationFailed) Fo.Failure else Fo.Muted, fontWeight = FontWeight.Medium)
                    }
                    if (t.inspectionId != null) PrimaryButton(if (s.linked?.stage == InspectionStage.VerifiedComplete) "See the FieldProof receipt" else "Open the FieldProof inspection", { onInspection(t.inspectionId) }, Modifier.fillMaxWidth())
                    else PrimaryButton("Verify with FieldProof", { vm.linkInspection(onInspection) }, Modifier.fillMaxWidth(), busy = s.busy == "link")
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Fo.Surface).border(1.dp, Fo.Border, RoundedCornerShape(18.dp))) {
                plan.steps.forEachIndexed { i, step ->
                    if (i > 0) HorizontalDivider(color = Fo.Border)
                    Column(Modifier.padding(14.dp)) {
                        Text("${i + 1}. ${step.title}", fontWeight = FontWeight.Medium, color = if (step.status == StepStatus.Done) Fo.Foreground else Fo.Muted)
                        step.lastCheck?.let { Text("AI check: ${verdictText(it.verdict)} · ${it.reason}", color = verdictColor(it.verdict), fontSize = 12.sp) }
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
    val t = s.task
    val camera = remember { HandleRef() }
    val open = t?.phase == TaskPhase.InProgress
    val answers = t?.answers.orEmpty().asReversed()
    var typed by remember { mutableStateOf("") }
    val pace = rememberVoicePace(vm.speaker, camera) { true }
    val voice = rememberVoiceInput { said ->
        when (parseCommand(said)) {
            VoiceCommand.Pause -> pace.pause(camera.value)
            VoiceCommand.Resume -> pace.resume(camera.value)
            VoiceCommand.Repeat -> answers.firstOrNull()?.let { vm.speaker.speak(it.answer, force = true) }
            VoiceCommand.Mute -> { vm.speaker.enabled = false; vm.speaker.stop() }
            VoiceCommand.Unmute -> vm.speaker.enabled = true
            VoiceCommand.Check -> camera.value?.capture?.invoke()
            else -> vm.ask(said, camera.value?.latestFrame?.invoke())
        }
    }
    CameraScreen(
        prompt = answers.firstOrNull()?.answer ?: "Point at anything and ask: an error on a screen, a part you don't recognise, a quick fix.",
        analyzeLive = vm::analyzeLiveFrame,
        onClose = onClose,
        onCaptured = { file -> vm.askAboutPhoto(LOOK, file) },
        header = "Just ask · no plan",
        onGuidance = pace::guidance,
        details = {
            if (pace.paused) PausedNote { pace.resume(camera.value) }
            val note = s.error ?: voice.error
            if (answers.isNotEmpty() || voice.listening || note != null || !open) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = .55f)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                answers.firstOrNull()?.let { Text("You asked: ${it.question}", color = FoDark.Muted, fontSize = 11.sp, maxLines = 2) }
                if (voice.listening) Text(voice.heard.ifEmpty { "Listening…" }, color = FoDark.Primary, fontSize = 13.sp)
                note?.let { Text(it, color = FoDark.Warning, fontSize = 12.sp) }
                if (!open) Text("This session has ended.", color = FoDark.Muted, fontSize = 12.sp)
            }
        },
        controls = { handle ->
            camera.value = handle
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    QuestionRow(typed, { typed = it }, voice.supported, voice.listening, s.busy == "ask", open, "Ask anything about what you see…",
                        onMic = { if (voice.listening) voice.stop() else voice.start() },
                        onSend = { vm.ask(typed, handle.latestFrame()); typed = "" })
                }
                StepButton(Icons.Outlined.CameraAlt, "Take a photo and ask what it shows", enabled = open && !handle.capturing && s.busy == null, busy = handle.capturing) { handle.capture() }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onPlan(answers.firstOrNull()?.question?.takeIf { it != LOOK }.orEmpty()) }) { Text("Make a plan", color = FoDark.Primary, fontSize = 12.sp) }
                Text("Say “repeat”, “check this”, or ask anything.", color = FoDark.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                if (open) TextButton(onClick = { vm.finish(onClose) }, enabled = s.busy == null) { Text("End", color = FoDark.Muted, fontSize = 12.sp) }
            }
        },
    )
}
