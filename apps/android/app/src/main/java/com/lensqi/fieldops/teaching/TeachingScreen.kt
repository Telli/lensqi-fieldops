package com.lensqi.fieldops.teaching

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lensqi.fieldops.ui.camera.findActivity
import com.lensqi.fieldops.voice.rememberVoiceInput

/** A separate foreground camera session: exports contain the camera video without the application's overlays. */
@Composable
fun TeachingScreen(vm: TeachingViewModel, onBack: () -> Unit) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var creating by rememberSaveable { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val active = ui.selected?.state in setOf(RecordingState.Preparing, RecordingState.Recording)
    val close = { if (active) vm.stop() else if (!ui.busy) { if (ui.selected != null) vm.select(null) else if (creating) creating = false else onBack() } }
    BackHandler { close() }
    DisposableEffect(owner) {
        vm.foregroundChanged(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.foregroundChanged(true)
            if (event == Lifecycle.Event.ON_STOP) vm.foregroundChanged(false)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { vm.foregroundChanged(false); owner.lifecycle.removeObserver(observer) }
    }
    // Rotation recreates the activity, which closes the camera and ends the recording; hold the orientation while recording.
    DisposableEffect(active) {
        val activity = context.findActivity()
        val previous = activity?.requestedOrientation
        if (active) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        onDispose { if (active && previous != null) activity.requestedOrientation = previous }
    }
    LaunchedEffect(ui.selected?.id) { if (ui.selected != null) creating = false }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.export { context.contentResolver.openOutputStream(uri, "w") }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = close, enabled = !ui.busy || active) { Text(if (active) "Stop & review" else "Back") }
            Text("Teaching recordings", style = MaterialTheme.typography.titleLarge)
        }
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        ui.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
        when {
            ui.loading -> CircularProgressIndicator(Modifier.padding(24.dp))
            creating || active -> CapturePanel(vm, ui, creating)
            ui.selected != null -> ReviewPanel(vm, ui, onExport = { export.launch("lensqi-teaching-${ui.selected!!.id}.zip") }, onDelete = { deleteId = ui.selected!!.id })
            else -> LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Record a demonstration, mark actions and review it for reuse. Video stays on this phone until you export it.") }
                item { Button(onClick = { vm.clearError(); creating = true }) { Text("Record a teaching session") } }
                if (ui.records.isEmpty()) item { Text("No recordings yet. A session can run for up to 10 minutes or 256 MB.", style = MaterialTheme.typography.bodySmall) }
                items(ui.records, key = { it.id }) { record ->
                    OutlinedCard(onClick = { vm.clearError(); vm.select(record.id) }, Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(record.goal, style = MaterialTheme.typography.titleMedium)
                            Text("${record.equipment} · ${record.state}")
                            Text(record.createdAt, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("Delete this recording?") },
        text = { Text("This removes the video and annotations from this phone. Exported copies are unaffected.") },
        confirmButton = { TextButton(onClick = { deleteId = null; vm.delete(id) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Keep") } }) }
}

@Composable
private fun CapturePanel(vm: TeachingViewModel, ui: TeachingUi, creating: Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var cameraAsked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; cameraAsked = true }
    var cameraReady by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.VIDEO_CAPTURE)
            videoCaptureQualitySelector = QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD))
        }
    }
    DisposableEffect(controller, owner, granted) {
        var disposed = false
        if (granted) {
            controller.initializationFuture.addListener({
                try { if (!disposed) { controller.initializationFuture.get(); controller.bindToLifecycle(owner); cameraReady = true } }
                catch (_: Exception) { cameraError = "The camera is unavailable. Close this screen and try again." }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose { disposed = true; vm.stop("Camera view closed."); controller.unbind() }
    }
    var goal by rememberSaveable { mutableStateOf(ui.task?.goal.orEmpty()) }
    var equipment by rememberSaveable { mutableStateOf(ui.task?.site?.assetId.orEmpty()) }
    var demonstrator by rememberSaveable { mutableStateOf("") }
    var permitted by rememberSaveable { mutableStateOf(false) }
    var training by rememberSaveable { mutableStateOf(false) }
    var stepIndex by rememberSaveable { mutableStateOf(0) }
    var action by rememberSaveable { mutableStateOf("Inspect") }
    var target by rememberSaveable { mutableStateOf("") }
    var actionMenu by remember { mutableStateOf(false) }
    var note by rememberSaveable { mutableStateOf("") }
    var noteSource by remember { mutableStateOf(TeachingSource.User) }
    val voice = rememberVoiceInput { note = it.take(2000); noteSource = TeachingSource.SpeechRecognition }
    val openAction = TeachingStore.openAction(ui.events)
    val recording = ui.selected?.state == RecordingState.Recording
    val steps = ui.task?.plan?.steps.orEmpty()
    val step = steps.getOrNull(stepIndex)

    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            if (granted && cameraError == null) AndroidView(
                factory = { PreviewView(it).apply { this.controller = controller; scaleType = PreviewView.ScaleType.FIT_CENTER; keepScreenOn = true } },
                modifier = Modifier.fillMaxWidth().height(220.dp),
            ) else Column {
                Text(cameraError ?: "Allow camera access to preview and record.")
                if (!granted) Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                if (!granted) com.lensqi.fieldops.ui.common.PermissionSettingsHint(context, Manifest.permission.CAMERA, cameraAsked, "Camera access", "Without it no demonstration can be recorded.")
            }
        }
        if (creating) {
            item { Text("Video has no audio. Add typed notes or tap the mic to dictate a note. Keep hands, tools and contact points in view.") }
            item { OutlinedTextField(goal, { goal = it.take(200) }, label = { Text("What are you demonstrating?") }, modifier = Modifier.fillMaxWidth(), enabled = !ui.busy) }
            item { OutlinedTextField(equipment, { equipment = it.take(200) }, label = { Text("Equipment / model / fixture") }, modifier = Modifier.fillMaxWidth(), enabled = !ui.busy) }
            item { OutlinedTextField(demonstrator, { demonstrator = it.take(80) }, label = { Text("Demonstrator alias") }, modifier = Modifier.fillMaxWidth(), enabled = !ui.busy) }
            item { ConsentRow(permitted, { permitted = it }, "I have permission to record the people, equipment and workspace shown.") }
            item { ConsentRow(training, { training = it }, "Allow this recording, annotations and attached task records to be used for model training when I export them.") }
            item { Text("Nothing is uploaded automatically. Training permission is optional. Review for private information before sharing.", style = MaterialTheme.typography.bodySmall) }
            item { Button(onClick = { vm.start(controller, goal, equipment, demonstrator, permitted, training) }, enabled = permitted && cameraReady && goal.trim().length >= 3 && equipment.isNotBlank() && demonstrator.isNotBlank() && !ui.busy) { Text(if (ui.busy) "Starting…" else "Start recording") } }
        } else {
            item { Text(if (recording) "Recording · ${ui.seconds / 60}:${(ui.seconds % 60).toString().padStart(2, '0')}" else "Preparing video…", style = MaterialTheme.typography.titleMedium) }
            item { Button(onClick = { if (voice.listening) voice.stop(); vm.stop() }, enabled = recording && !ui.busy) { Text(if (ui.busy) "Saving…" else "Stop & save") } }
            if (step != null) {
                item {
                    Text("Attached AI plan · ${ui.task?.plan?.sourceNote.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                    if (ui.task?.plan?.professionalRequired == true) Text("This task needs a professional. ${ui.task.plan.professionalReason.orEmpty()}")
                }
                item { Text("Step ${stepIndex + 1}: ${step.title}", style = MaterialTheme.typography.titleMedium); Text(step.instruction); step.cautions.forEach { Text(it.text) }; step.stopIf.forEach { Text("Stop if $it") } }
                item { Row {
                    TextButton(onClick = { stepIndex-- }, enabled = stepIndex > 0) { Text("Previous") }
                    TextButton(onClick = { vm.annotate(TeachingKind.Step, step.title, step.id) }, enabled = recording && !ui.busy) { Text("Mark this step") }
                    TextButton(onClick = { stepIndex++ }, enabled = stepIndex + 1 < steps.size) { Text("Next") }
                }; Text("Markers describe this demonstration; they do not complete task steps.", style = MaterialTheme.typography.bodySmall) }
            }
            item { Row(verticalAlignment = Alignment.CenterVertically) {
                Box { OutlinedButton(onClick = { actionMenu = true }, enabled = openAction == null && !ui.busy) { Text(action) }
                    DropdownMenu(expanded = actionMenu, onDismissRequest = { actionMenu = false }) { TeachingStore.actionTypes.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { action = value; actionMenu = false }) } }
                }
                OutlinedTextField(target, { target = it.take(200) }, label = { Text("Object / tool") }, modifier = Modifier.weight(1f), enabled = openAction == null && !ui.busy)
            } }
            item { if (openAction == null) Button(onClick = { vm.annotate(TeachingKind.ActionStart, "$action $target", step?.id, actionType = action, objectName = target.trim()) }, enabled = recording && target.isNotBlank() && !ui.busy) { Text("Start action") }
                else Button(onClick = { vm.annotate(TeachingKind.ActionEnd, "Action ended", refersTo = openAction.sequence) }, enabled = recording && !ui.busy) { Text("End: ${openAction.text}") } }
            item { OutlinedTextField(note, { note = it.take(2000); noteSource = TeachingSource.User }, label = { Text("Observation, mistake or correction") }, modifier = Modifier.fillMaxWidth(), enabled = recording && !ui.busy) }
            item { Row {
                if (voice.supported) TextButton(onClick = { if (voice.listening) voice.stop() else voice.start() }, enabled = recording && !ui.busy) { Text(if (voice.listening) "Stop listening" else "Dictate note") }
                Button(onClick = { vm.annotate(TeachingKind.Note, note, step?.id, source = noteSource); note = "" }, enabled = recording && note.isNotBlank() && !voice.listening && !ui.busy) { Text("Add note") }
            }; voice.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }; if (voice.listening) Text("Listening… ${voice.heard}") }
            item { Text("Dictation uses the phone’s speech service, which may process audio remotely. Only the resulting text is saved in the recording.", style = MaterialTheme.typography.bodySmall) }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun ConsentRow(checked: Boolean, onChange: (Boolean) -> Unit, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, onChange); Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)) }
}

@Composable
private fun ReviewPanel(vm: TeachingViewModel, ui: TeachingUi, onExport: () -> Unit, onDelete: () -> Unit) {
    val record = ui.selected ?: return
    var correction by remember { mutableStateOf<TeachingEvent?>(null) }
    var replacement by remember { mutableStateOf("") }
    var correctedAction by remember { mutableStateOf("Inspect") }
    var correctedObject by remember { mutableStateOf("") }
    var correctionMenu by remember { mutableStateOf(false) }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(record.goal, style = MaterialTheme.typography.titleLarge); Text("${record.equipment} · ${record.state}") }
        if (record.video != null) item {
            AndroidView(factory = { ctx -> VideoView(ctx).apply { setVideoPath(vm.store.video(record.id).path); setMediaController(MediaController(ctx).also { it.setAnchorView(this) }); setOnPreparedListener { seekTo(1) } } },
                modifier = Modifier.fillMaxWidth().height(230.dp), onRelease = { it.stopPlayback() })
            Text("Tap the video to play and review.", style = MaterialTheme.typography.bodySmall)
            Text("${record.video.width} × ${record.video.height} · ${record.video.frameCount} frames · ${record.video.largeGapCount} large frame gaps", style = MaterialTheme.typography.bodySmall)
        }
        record.stopReason?.let { reason -> item {
            Text(if (reason.startsWith("CameraX finalization code")) "The camera stopped before recording finished." else reason, style = MaterialTheme.typography.bodySmall)
        } }
        item { Text(if (record.consent.trainingAllowed) "Training permission granted by recorder." else "Training permission not granted.") }
        if (record.video != null) {
            item { Text("Your assessment of this demonstration"); Row { TeachingStore.outcomes.forEach { result -> TextButton(onClick = { vm.annotate(TeachingKind.Outcome, result) }, enabled = !ui.busy) { Text(result) } } } }
            item { Text("This is your assessment, separate from any AI suggestion or equipment diagnostic.", style = MaterialTheme.typography.bodySmall) }
        }
        items(ui.events.filter { it.kind in TeachingStore.editableKinds || it.kind == TeachingKind.Correction }, key = { it.sequence }) { event ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text("#${event.sequence} · ${event.kind} · ${event.source}", style = MaterialTheme.typography.labelMedium)
                event.videoOffsetEstimateNs?.let { Text("About ${it / 1_000_000_000}s into video", style = MaterialTheme.typography.bodySmall) }
                Text(event.text)
                if (event.kind == TeachingKind.Correction) Text("Corrects annotation #${event.refersTo}", style = MaterialTheme.typography.bodySmall)
                // A new outcome supersedes the old one, so outcomes are set again rather than corrected as free text.
                else if (record.video != null && event.kind != TeachingKind.Outcome) TextButton(onClick = { correction = event; replacement = event.text; correctedAction = event.actionType ?: "Inspect"; correctedObject = event.objectName.orEmpty() }, enabled = !ui.busy) { Text("Correct annotation") }
            } }
        }
        if (record.video != null) {
            item { if (ui.reviewed) Text("Video and annotations reviewed by recorder.") else Button(onClick = { vm.annotate(TeachingKind.Review, "Recorder reviewed the video, annotations and permission for export.") }, enabled = !ui.busy && ui.events.any { it.kind == TeachingKind.Outcome }) { Text("Mark video & annotations reviewed") } }
            item { Text("Export includes original video, annotations, frame timestamps and any attached task records. Video alignment to annotations is approximate; hand motion, depth and robot actions are not captured.", style = MaterialTheme.typography.bodySmall) }
            item { Button(onClick = onExport, enabled = !ui.busy) { Text(if (ui.busy) "Working…" else "Export ZIP") } }
        }
        item { TextButton(onClick = onDelete, enabled = !ui.busy) { Text("Delete from phone", color = MaterialTheme.colorScheme.error) } }
    }
    correction?.let { event -> AlertDialog(onDismissRequest = { correction = null }, title = { Text("Correct annotation #${event.sequence}") },
        text = { Column {
            OutlinedTextField(replacement, { replacement = it.take(2000) }, label = { Text("Correction (original is retained)") })
            if (event.kind == TeachingKind.ActionStart) {
                Box { TextButton(onClick = { correctionMenu = true }) { Text(correctedAction) }
                    DropdownMenu(correctionMenu, { correctionMenu = false }) { TeachingStore.actionTypes.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { correctedAction = value; correctionMenu = false }) } }
                }
                OutlinedTextField(correctedObject, { correctedObject = it.take(200) }, label = { Text("Corrected object / tool") })
            }
        } },
        confirmButton = { TextButton(onClick = { vm.annotate(TeachingKind.Correction, replacement, refersTo = event.sequence, actionType = if (event.kind == TeachingKind.ActionStart) correctedAction else null, objectName = if (event.kind == TeachingKind.ActionStart) correctedObject else null); correction = null }, enabled = replacement.isNotBlank()) { Text("Save correction") } },
        dismissButton = { TextButton(onClick = { correction = null }) { Text("Cancel") } }) }
}
