package com.lensqi.fieldops.ui.glasses

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.graphics.Paint
import android.os.Build
import android.speech.tts.TextToSpeech
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lensqi.fieldops.ui.common.Dot
import com.lensqi.fieldops.ui.common.Panel
import com.lensqi.fieldops.ui.common.PrimaryButton
import com.lensqi.fieldops.ui.common.SecondaryButton
import com.lensqi.fieldops.ui.common.SectionTitle
import com.lensqi.fieldops.ui.common.SimBadge
import com.lensqi.fieldops.ui.theme.Eyebrow
import com.lensqi.fieldops.ui.theme.Fo
import com.lensqi.fieldops.ui.theme.FoDark
import com.lensqi.fieldops.wearables.GlassesMode
import com.lensqi.fieldops.wearables.LiveSession
import com.lensqi.fieldops.wearables.GlassesLiveService
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.lensqi.fieldops.wearables.GlassesPhase
import com.lensqi.fieldops.wearables.GlassesState
import com.lensqi.fieldops.ui.camera.LiveCameraState
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.launch
import java.io.File

/**
 * Hands-free capture with Meta glasses (Wearables Device Access Toolkit). The wearer hears [prompt] (and sees it on a
 * Meta Ray-Ban Display), sees the glasses' camera here, and captures from the phone, the glasses' button or the display.
 * [onCaptured] gets an upright JPEG and its evidence origin: "Glasses" for real glasses, "MockWearables" for the mock.
 */
@Composable
fun GlassesScreen(
    session: LiveSession,
    prompt: String,
    status: String?,
    onClose: () -> Unit,
    onCaptured: (file: File, origin: String) -> Unit,
    vm: GlassesViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val liveActive by rememberUpdatedState(live.enabled)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP && !liveActive) vm.stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    // One service runs live assist; it is "here" only while it serves this inspection or step. Starting it here switches.
    val liveHere = live.enabled && session.sameAs(live.session)
    val captured by rememberUpdatedState(onCaptured)
    val capture = { vm.capture(session) { photo -> captured(photo.file, photo.origin) } }
    // Without the notification (permission refused, app notifications off, or the channel blocked) the service still runs,
    // but its Stop and Mute actions are not on the lock screen; the screen says so instead of promising them. Checked again
    // each time the screen resumes, since Settings can change it meanwhile.
    var notificationsOn by remember { mutableStateOf(GlassesLiveService.notificationsAvailable(context)) }
    LifecycleResumeEffect(Unit) { notificationsOn = GlassesLiveService.notificationsAvailable(context); onPauseOrDispose { } }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsOn = GlassesLiveService.notificationsAvailable(context); vm.startLive(session) }
    val startLive = {
        if (Build.VERSION.SDK_INT >= 33 && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else vm.startLive(session)
    }
    // A shutter photo paused live assist for this session: once the camera streams again here, and the photo is taken, it
    // carries on by itself. Keyed on capturing too, so the check runs again when the shutter finishes.
    LaunchedEffect(s.streaming, live.enabled, s.capturing) { if (vm.consumeResume(session)) startLive() }

    var bluetoothDenied by remember { mutableStateOf(false) }
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) { bluetoothDenied = false; vm.initialize() } else bluetoothDenied = true
    }
    LaunchedEffect(Unit) {
        if (granted(context, Manifest.permission.BLUETOOTH_CONNECT)) vm.initialize()
        else askBluetooth.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
    }
    // Camera access on the glasses is granted in the Meta AI app (the mock grants it itself).
    val askGlassesCamera = rememberLauncherForActivityResult(Wearables.RequestPermissionContract()) { result ->
        if (result.getOrNull() == PermissionStatus.Granted) vm.start() else vm.report("Camera access on the glasses was not granted in Meta AI.")
    }
    val startCamera: () -> Unit = { scope.launch { if (vm.cameraPermissionGranted()) vm.start() else askGlassesCamera.launch(Permission.CAMERA) } }
    val askPhoneCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.useMockPhoneCameraFeed() else vm.report("Camera permission is needed to use the phone camera as the mock feed.")
    }
    val pickMockImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.setMockCapturedImage(uri) }
    val pickMockVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.setMockVideoFeed(uri) }

    // Formatted once, so the glasses show the same words they speak.
    val readablePrompt = remember(prompt) { com.lensqi.fieldops.ui.common.TechnicianText.guidance(prompt) }
    LaunchedEffect(readablePrompt, status, liveHere) { if (!liveHere) vm.showHud(readablePrompt, status) }
    LaunchedEffect(Unit) { vm.shutter.collect { capture() } }

    var muted by rememberSaveable { mutableStateOf(true) }
    val speaker = rememberSpeaker()
    LaunchedEffect(readablePrompt, speaker, muted, liveHere) {
        if (muted || liveHere) speaker?.stop() else speaker?.speak(readablePrompt, TextToSpeech.QUEUE_FLUSH, null, "glasses-prompt")
    }

    val mock = s.mode == GlassesMode.Mock
    Column(Modifier.fillMaxSize().background(Fo.Background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close glasses capture") }
            Text("Glasses assist", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (mock) SimBadge("Mock glasses", Fo.Warning)
            Spacer(Modifier.width(8.dp))
            StatusChip(s.phase)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PreviewPanel(s, vm, if (liveHere) live.feedback else null)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (liveHere) {
                    // What the assistance is about, then the session's status (it says when guidance has stalled).
                    session.label?.let { Text("Assist for: $it", style = MaterialTheme.typography.bodySmall) }
                    Text(live.feedback.status, color = Fo.Primary, fontWeight = FontWeight.Medium)
                    if (com.lensqi.fieldops.ui.common.LocalDiagnostics.current) Text("${live.feedback.fps} tracking updates/s · AI guidance ${live.feedback.guidanceAge?.let { "from ${it}s ago" } ?: "updating"}", style = MaterialTheme.typography.bodySmall)
                    SecondaryButton("Pause live assist", vm::pauseLive, Modifier.fillMaxWidth())
                    Text(if (notificationsOn) "Active with the phone locked. Stop the camera from its notification; if the lock screen hides notifications, unlock the phone to reach it."
                        else "Active with the phone locked. Notifications are off, so there is no Stop or Mute on the lock screen: pause live assist from this screen.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    if (live.enabled) Text("Live assist is still running for ${live.session?.label ?: "another inspection or step"}. Start it here to switch.", color = Fo.Warning, style = MaterialTheme.typography.bodySmall)
                    PrimaryButton("Start live assist", startLive, Modifier.fillMaxWidth(), enabled = s.streaming)
                    Text("Tracks what your glasses see and speaks guidance. Sampled frames go to AI, including with the phone locked. Only shutter photos are saved.", style = MaterialTheme.typography.bodySmall)
                }
                live.audioNote?.let { Text(it, color = Fo.Warning, style = MaterialTheme.typography.bodySmall) }
            }
            HudPanel(if (liveHere) com.lensqi.fieldops.ui.common.TechnicianText.guidance(live.feedback.recommendation) else readablePrompt,
                if (liveHere) "Camera guidance · inferred" else status, s, if (liveHere) live.muted else muted) {
                if (liveHere) vm.muteLive() else muted = !muted
            }
            s.error?.let { Text(it, color = Fo.Failure, style = MaterialTheme.typography.bodySmall) }
            Controls(
                s, bluetoothDenied,
                onBluetooth = { askBluetooth.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) },
                onRegister = { context.findActivity()?.let(vm::register) },
                onStart = startCamera,
                onStop = vm::stop,
                onCapture = capture,
            )
            if (s.mockAvailable) MockPanel(
                s, vm,
                onPickImage = { pickMockImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onPickVideo = { pickMockVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                onPhoneFeed = { if (granted(context, Manifest.permission.CAMERA)) vm.useMockPhoneCameraFeed() else askPhoneCamera.launch(Manifest.permission.CAMERA) },
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatusChip(phase: GlassesPhase) {
    val color = when (phase) {
        GlassesPhase.Streaming -> Fo.Success
        GlassesPhase.Connected, GlassesPhase.Starting -> Fo.Primary
        GlassesPhase.Registering, GlassesPhase.Paused -> Fo.Warning
        else -> Fo.Muted
    }
    Row(
        Modifier.background(color.copy(alpha = 0.1f), RoundedCornerShape(50)).border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Dot(color)
        Text(phase.label, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

/** The glasses' camera, decoded on the phone. Nothing is shown unless frames really arrive from the SDK. */
@Composable
private fun PreviewPanel(s: GlassesState, vm: GlassesViewModel, feedback: LiveCameraState?) {
    val ratio = if (s.frameWidth > 0 && s.frameHeight > 0) s.frameWidth.toFloat() / s.frameHeight else 9f / 16f
    val live = s.stream != null
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Fo.Overlay).border(1.dp, Fo.Border, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (live) {
            Box(Modifier.heightIn(max = 400.dp).aspectRatio(ratio, matchHeightConstraintsFirst = true)) {
                AndroidView(factory = { ctx -> previewView(ctx, vm) }, modifier = Modifier.fillMaxSize())
                if (feedback != null) Canvas(Modifier.fillMaxSize()) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 12.sp.toPx(); setShadowLayer(5f, 0f, 0f, android.graphics.Color.BLACK) }
                    for ((n, part) in feedback.objects.withIndex()) {
                        val b = part.box; val x = (b.left * size.width).toFloat(); val y = (b.top * size.height).toFloat()
                        drawRect(FoDark.Primary, Offset(x, y), Size(((b.right - b.left) * size.width).toFloat(), ((b.bottom - b.top) * size.height).toFloat()), style = Stroke(2.dp.toPx()))
                        drawContext.canvas.nativeCanvas.drawText("${n + 1}. ${part.label}", x + 4.dp.toPx(), maxOf(16.dp.toPx(), y - 4.dp.toPx()), paint)
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().height(220.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.Videocam, null, tint = Fo.Muted, modifier = Modifier.size(32.dp))
                Text(placeholder(s), color = Fo.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.align(Alignment.TopStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.mode == GlassesMode.Mock) SimBadge("Mock glasses", FoDark.Warning)
            if (s.streaming) GlassLabel("${s.deviceName ?: "Glasses"} · live")
        }
        if (s.capturing) CircularProgressIndicator(Modifier.size(36.dp), color = Fo.Primary)
    }
}

private fun placeholder(s: GlassesState) = when (s.phase) {
    GlassesPhase.Unavailable -> if (s.sdkReady) "Glasses registration is unavailable: the Meta AI app is needed." else "Glasses support starts once Bluetooth access is allowed."
    GlassesPhase.NotRegistered -> "Connect LensQi FieldOps to your glasses in the Meta AI app."
    GlassesPhase.Registering -> "Finish connecting in the Meta AI app."
    GlassesPhase.NoGlasses -> "Turn on and unfold your glasses, and keep them near the phone."
    else -> "Start the glasses camera to see what the glasses see."
}

private fun previewView(ctx: Context, vm: GlassesViewModel) = TextureView(ctx).apply {
    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        private var surface: Surface? = null
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            surface = Surface(texture).also(vm::setSurface)
        }
        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            vm.setSurface(null); surface?.release(); surface = null
            return true
        }
        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }
}

@Composable
private fun GlassLabel(text: String) = Text(
    text, fontSize = 11.sp, color = FoDark.Foreground,
    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xB30B0F14)).padding(horizontal = 6.dp, vertical = 2.dp),
)

/** What the wearer is told: spoken (to the glasses' speakers over Bluetooth) and, on a Ray-Ban Display, shown in the lens. */
@Composable
private fun HudPanel(prompt: String, status: String?, s: GlassesState, muted: Boolean, onToggleMute: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Fo.Surface).border(1.dp, Fo.Primary.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("INSTRUCTION", style = Eyebrow.copy(color = Fo.Primary), modifier = Modifier.weight(1f))
            if (s.mode == GlassesMode.Mock) SimBadge("Mock glasses", Fo.Warning)
            IconButton(onClick = onToggleMute, modifier = Modifier.size(32.dp)) {
                Icon(if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp, if (muted) "Unmute instruction" else "Mute instruction", tint = if (muted) Fo.Muted else Fo.Primary, modifier = Modifier.size(20.dp))
            }
        }
        Text(prompt, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        status?.let { Text(it, color = Fo.Muted, style = MaterialTheme.typography.bodySmall) }
        Text(
            when {
                s.displayActive -> "Shown on the glasses display."
                s.displayCapable && s.session != null -> "Glasses display not ready yet."
                s.displayCapable -> "Start the glasses camera to show instructions on the display."
                else -> "No display on these glasses: the instruction is spoken only."
            } + if (muted) " Voice muted." else " Read aloud through the phone's audio, which plays on the glasses when they are connected over Bluetooth.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Controls(
    s: GlassesState,
    bluetoothDenied: Boolean,
    onBluetooth: () -> Unit,
    onRegister: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCapture: () -> Unit,
) {
    when (s.phase) {
        GlassesPhase.Unavailable ->
            if (bluetoothDenied || !s.sdkReady) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LensQi needs Bluetooth access to reach your glasses.", style = MaterialTheme.typography.bodySmall)
                PrimaryButton("Allow Bluetooth", onBluetooth, icon = Icons.Outlined.Bluetooth)
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Glasses registration is unavailable on this phone. Install the Meta AI app, pair your glasses there, then connect.", style = MaterialTheme.typography.bodySmall)
                SecondaryButton("Connect with Meta AI", onRegister, icon = Icons.Outlined.Link)
            }
        GlassesPhase.NotRegistered -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Connect with Meta AI", onRegister, icon = Icons.Outlined.Link)
            Text("Opens the Meta AI app, where you approve LensQi FieldOps for your glasses.", style = MaterialTheme.typography.bodySmall)
        }
        GlassesPhase.Registering -> PrimaryButton("Waiting for Meta AI…", {}, busy = true)
        GlassesPhase.NoGlasses, GlassesPhase.Connected -> PrimaryButton("Start glasses camera", onStart, icon = Icons.Outlined.Videocam)
        GlassesPhase.Starting, GlassesPhase.Paused, GlassesPhase.Streaming -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            // With the phone camera as the mock feed the kit returns a phone-camera still, so no chosen image is needed.
            val needsImage = s.mode == GlassesMode.Mock && !s.mockCapturedImageSet && s.mockFeed != "Phone camera"
            val enabled = s.streaming && !s.capturing && !needsImage
            Box(
                Modifier.size(78.dp).border(3.dp, if (enabled) Fo.Foreground else Fo.Muted, CircleShape).padding(6.dp).clip(CircleShape)
                    .background(if (enabled) Fo.Foreground else Fo.Muted.copy(alpha = 0.4f)).clickable(enabled = enabled, onClick = onCapture),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.PhotoCamera, "Capture from the glasses", tint = Fo.Background) }
            Text(
                when {
                    needsImage -> "Choose the image the mock glasses return before capturing."
                    !s.streaming -> if (s.phase == GlassesPhase.Paused) "Paused on the glasses. Tap the touchpad to resume." else "Starting the glasses camera…"
                    s.glassesButtonActive -> "Or press the capture button on your glasses."
                    else -> "Capture from here. The glasses' own button is not available to this app" + (s.glassesButtonNote?.let { " ($it)." } ?: ".")
                },
                style = MaterialTheme.typography.bodySmall,
            )
            SecondaryButton("Stop glasses camera", onStop)
        }
    }
}

/** Debug builds only. Meta's Mock Device Kit: simulated glasses, clearly badged, uploaded as MockWearables. */
@Composable
private fun MockPanel(s: GlassesState, vm: GlassesViewModel, onPickImage: () -> Unit, onPickVideo: () -> Unit, onPhoneFeed: () -> Unit) {
    var withDisplay by rememberSaveable { mutableStateOf(false) }
    val on = s.mode == GlassesMode.Mock
    Panel(border = Fo.Warning.copy(alpha = 0.35f)) {
        SectionTitle("Developer · Mock Device Kit") { SimBadge("Debug build", Fo.Muted) }
        Text("Simulated Ray-Ban Meta glasses from Meta's Mock Device Kit. Their photos upload as MockWearables, never as glasses evidence.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mock glasses", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Switch(on, { if (it) vm.enableMock(withDisplay) else vm.disableMock() }, colors = SwitchDefaults.colors(checkedTrackColor = Fo.Warning, checkedThumbColor = Fo.OnPrimary))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Simulate Meta Ray-Ban Display", style = MaterialTheme.typography.bodyMedium)
                Text(if (on) "Turn mock glasses off to change." else "Mock a pair with a display, to see the instruction in the lens.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(withDisplay, { withDisplay = it }, enabled = !on, colors = SwitchDefaults.colors(checkedTrackColor = Fo.Primary, checkedThumbColor = Fo.OnPrimary))
        }
        if (on) {
            Text("Captured image: " + if (s.mockCapturedImageSet) "chosen" else "not set", style = MaterialTheme.typography.bodySmall, color = if (s.mockCapturedImageSet) Fo.Success else Fo.Warning)
            SecondaryButton("Choose captured image", onPickImage, icon = Icons.Outlined.Image)
            if (s.mockFeed == "Phone camera") Text("With the phone camera as the feed, the kit returns a phone camera still instead of the chosen image.", style = MaterialTheme.typography.bodySmall, color = Fo.Warning)
            Text("Video feed: " + (s.mockFeed ?: "none (the mock stream needs one)"), style = MaterialTheme.typography.bodySmall, color = if (s.mockFeed != null) Fo.Success else Fo.Warning)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Video clip", onPickVideo, Modifier.weight(1f), icon = Icons.Outlined.Movie)
                SecondaryButton("Phone camera", onPhoneFeed, Modifier.weight(1f), icon = Icons.Outlined.Videocam)
            }
            SecondaryButton("Press glasses capture button", vm::pressMockCaptureButton, enabled = s.streaming, icon = Icons.Outlined.TouchApp)
            if (s.displayCapable) {
                Text("MOCK DISPLAY", style = Eyebrow)
                Box(Modifier.fillMaxWidth().height(340.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
                    AndroidView(factory = { ctx -> vm.mockDisplayPreview(ctx) ?: android.view.View(ctx) }, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun rememberSpeaker(): TextToSpeech? {
    val context = LocalContext.current
    var ready by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context.applicationContext) { status -> if (status == TextToSpeech.SUCCESS) ready = tts }
        onDispose { tts.stop(); tts.shutdown(); ready = null }
    }
    return ready
}

private fun granted(context: Context, permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
