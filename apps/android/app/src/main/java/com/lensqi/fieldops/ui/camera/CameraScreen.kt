package com.lensqi.fieldops.ui.camera

import com.lensqi.fieldops.ui.common.TechnicianText
import com.lensqi.fieldops.ui.common.LocalDiagnostics
import com.lensqi.fieldops.data.diagnostics.DiagnosticHistory

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import com.lensqi.fieldops.ui.common.LensQiMark
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.lensqi.fieldops.arcore.partsToShow
import com.lensqi.fieldops.domain.live.LiveObject
import com.lensqi.fieldops.domain.live.staleAlpha
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Check
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.lensqi.fieldops.ui.common.SecondaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Path
import com.lensqi.fieldops.arcore.SpatialControl
import com.lensqi.fieldops.arcore.TargetState
import com.lensqi.fieldops.arcore.AssetAnswer
import com.lensqi.fieldops.arcore.AssetLayoutStore
import com.lensqi.fieldops.arcore.GuideLevel
import com.lensqi.fieldops.arcore.RegistrationSubject
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.lensqi.fieldops.ui.common.PrimaryButton
import com.lensqi.fieldops.ui.theme.FoDark
import com.lensqi.fieldops.ui.theme.ForceDark
import java.io.File
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.opengl.GLSurfaceView
import android.view.WindowManager
import androidx.lifecycle.Observer
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.lensqi.fieldops.arcore.ArAvailability
import com.lensqi.fieldops.arcore.ArCore
import com.lensqi.fieldops.arcore.ArLiveRenderer
import kotlinx.coroutines.delay
import androidx.camera.core.AspectRatio
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.CameraController
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.TextButton
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lensqi.fieldops.domain.live.LiveResponse
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * User-triggered capture of a full-resolution JPEG (no downscaling, no filters). The file is handed to the queue,
 * which hashes it on device and uploads the original bytes with source=Phone. [prompt] is the view the server asked for.
 */
@Composable
fun CameraScreen(
    prompt: String, analyzeLive: suspend (String, ByteArray, String) -> LiveResponse, onClose: () -> Unit, onCaptured: (File) -> Unit,
    header: String? = null, onGuidance: (String) -> Unit = {},
    details: (@Composable ColumnScope.() -> Unit)? = null, controls: (@Composable (CameraHandle) -> Unit)? = null,
    targetConcept: String? = null,
    liveAllowed: Boolean = true,
    /** One line shown while the details are collapsed, such as the step's first "stop if"; tapping it expands them. */
    detailsSummary: String? = null,
    /** Keeps the details open while something in them needs attention (an answer, an error, listening). */
    showDetails: Boolean = false,
    /** The parts on screen after each analysed frame, with ARCore's distance and height where measured. */
    onObjects: (List<LiveObject>) -> Unit = {},
    /** The step, task or inspection on screen; when it changes, every label, anchor, answer and target from before goes. */
    scopeKey: Any? = null,
    /** Where the confirmed part is remembered across AR sessions; a view model's, so it outlives this screen. */
    targetMemory: TargetMemory? = null,
    /** The distance range in cm the task or view request asks for, if it gives one. */
    framingCm: IntRange? = null,
    /** The part the server compares for this photo; after the shutter the technician checks the still shows it. */
    capturePart: String? = null,
    /** The before photo the server compares against, shown over the view as a framing aid while lining up. */
    reference: Bitmap? = null,
    /** The equipment to register with the printed marker and the port the step needs; null where there is none. */
    registration: RegistrationSubject? = null,
    /** The shutter press itself, before live assist stops and before any confirmation of the still: what the photo is of is decided now. */
    onShutter: () -> Unit = {},
    /**
     * Tasks only: a tap on a drawn outline offers "Ask about the <part>", which hands the part here; the screen asks its
     * question with the live frame, as a spoken question would be. Null leaves the outlines display-only.
     */
    onAskPart: ((LiveObject) -> Unit)? = null,
) = ForceDark {
    CameraContent(prompt, analyzeLive, onClose, onCaptured, header, onGuidance, details, controls, targetConcept, liveAllowed, detailsSummary, showDetails, onObjects,
        scopeKey, targetMemory ?: remember { TargetMemory() }, framingCm, capturePart, reference, registration, onShutter, onAskPart)
}

/** What a screen that embeds the camera can do with it: take the full-resolution photo, or read the latest live frame. */
class CameraHandle(val capture: () -> Unit, val latestFrame: () -> ByteArray?, val capturing: Boolean, val live: Boolean, val setLive: (Boolean) -> Unit = {})

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CameraContent(
    prompt: String, analyzeLive: suspend (String, ByteArray, String) -> LiveResponse, onClose: () -> Unit, onCaptured: (File) -> Unit,
    header: String?, onGuidance: (String) -> Unit, details: (@Composable ColumnScope.() -> Unit)?, controls: (@Composable (CameraHandle) -> Unit)?,
    targetConcept: String?,
    liveAllowed: Boolean,
    detailsSummary: String?,
    showDetails: Boolean,
    onObjects: (List<LiveObject>) -> Unit,
    scopeKey: Any?,
    memory: TargetMemory,
    framingCm: IntRange?,
    capturePart: String?,
    reference: Bitmap?,
    registrationSubject: RegistrationSubject?,
    onShutter: () -> Unit,
    onAskPart: ((LiveObject) -> Unit)?,
) {
    // The view stays clear: details start collapsed, and a tap on the picture hides every panel but the controls.
    var expanded by rememberSaveable { mutableStateOf(false) }
    var hideChrome by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var status by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }
    // Survive rotation: turning the phone must not switch live assist or 3D AR off.
    var liveEnabled by rememberSaveable { mutableStateOf(false) }
    // Live guidance follows the screen's subject; when the screen says it does not apply (an earlier step is shown), it stops.
    LaunchedEffect(liveAllowed) { if (!liveAllowed) liveEnabled = false }
    var arAvailability by remember { mutableStateOf(ArCore.availability(context)) }
    LaunchedEffect(arAvailability) { if (arAvailability == ArAvailability.Checking) { delay(250); arAvailability = ArCore.availability(context) } }
    var spatialEnabled by rememberSaveable { mutableStateOf(true) }
    var arFailure by remember { mutableStateOf<String?>(null) }
    var pendingCapture by remember { mutableStateOf(false) }
    // The still waiting for "Is this the …?", kept by path so it survives process death; the part asked about; and
    // whether live assist was on at the shutter, so a retake turns it back on and AR can find the target again.
    var still by rememberSaveable { mutableStateOf<String?>(null) }
    var stillPart by rememberSaveable { mutableStateOf<String?>(null) }
    var liveBeforeShutter by rememberSaveable { mutableStateOf(false) }
    var ghost by rememberSaveable { mutableStateOf(true) }
    val arActive = liveEnabled && spatialEnabled && arAvailability == ArAvailability.Ready && arFailure == null
    var asked by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    // Back from Settings: what was allowed there takes effect without reopening the camera.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; onPauseOrDispose { } }

    if (!granted) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
            Text("Camera access is needed to capture evidence. Nothing is recorded without your tap.")
            PrimaryButton("Allow camera", { ask.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth())
            com.lensqi.fieldops.ui.common.PermissionSettingsHint(context, Manifest.permission.CAMERA, asked, "Camera access", "Without it no photo can be taken, so nothing can be inspected or verified.")
            Text("Back", color = FoDark.Muted, modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
        }
        return
    }

    val controller = remember {
        LifecycleCameraController(context).apply {
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            imageAnalysisTargetSize = CameraController.OutputSize(android.util.Size(640, 480))
            previewTargetSize = CameraController.OutputSize(AspectRatio.RATIO_4_3)
        }
    }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; this.controller = controller } }
    var streaming by remember { mutableStateOf(false) }
    DisposableEffect(previewView, lifecycleOwner) {
        val observer = Observer<PreviewView.StreamState> { streaming = it == PreviewView.StreamState.STREAMING }
        previewView.previewStreamState.observe(lifecycleOwner, observer)
        onDispose { previewView.previewStreamState.removeObserver(observer) }
    }
    // ARCore and CameraX cannot hold the camera at once: CameraX steps aside while AR runs.
    LaunchedEffect(arActive) { if (arActive) { streaming = false; controller.unbind() } else controller.bindToLifecycle(lifecycleOwner) }

    // An embedding screen (a task step) stays on this camera after the photo, so the coaching it had before the shutter comes
    // back; a standalone camera leaves for the review, so nothing is restored there.
    // Live coaching was turned off for the shutter; it comes back whether the photo was taken, refused or never attempted.
    fun restoreLive() { liveEnabled = liveBeforeShutter && liveAllowed }
    fun deliver(file: File) {
        if (controls != null) restoreLive()
        onCaptured(file)
    }
    fun takePhoto() {
        val file = File(context.cacheDir, "capture-${System.currentTimeMillis()}.jpg")
        controller.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) { capturing = false; if (stillPart != null) still = file.path else deliver(file) }
                override fun onError(exception: ImageCaptureException) { capturing = false; restoreLive(); DiagnosticHistory.record("capture", "capture_failed", exception.imageCaptureError); status = "The photo couldn’t be taken. Hold steady and try again." }
            },
        )
    }
    // A shutter press during AR waits for CameraX, so the evidence photo is always full resolution.
    LaunchedEffect(pendingCapture, streaming, arActive) { if (pendingCapture && streaming && !arActive) { pendingCapture = false; takePhoto() } }
    // If CameraX doesn't come back after AR hands the camera over, say so and let the technician press again.
    LaunchedEffect(pendingCapture) {
        if (pendingCapture) { delay(8_000); pendingCapture = false; capturing = false; restoreLive(); status = "The camera isn't ready. Press the shutter again." }
    }
    fun retake() {
        still?.let { File(it).delete() }
        still = null
        liveEnabled = liveBeforeShutter && liveAllowed
    }
    fun useStill() { val path = still ?: return; still = null; deliver(File(path)) }
    // A still restored after process death whose file is gone has nothing to confirm.
    LaunchedEffect(still) { if (still?.let { File(it).exists() } == false) still = null }
    BackHandler(enabled = still != null) { retake() }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val session = remember(liveEnabled) { if (liveEnabled) LiveCameraSession(analyze = analyzeLive) else null }
    // Distance coaching follows the current step's part, also when the step changes while live assist runs.
    SideEffect { session?.targetConcept = targetConcept; session?.framingCm = framingCm }
    val spatial = remember { SpatialControl() }
    var pickingFloor by remember { mutableStateOf(false) }
    // The drawn part last tapped, while its options (ask about it, hold it as the AR target) are offered.
    var tapped by remember { mutableStateOf<LiveObject?>(null) }
    var tapNumber by remember { mutableStateOf(0) }
    // A new step, task or inspection starts clean: no labels, anchors, answers or target from the one before.
    LaunchedEffect(scopeKey) {
        if (memory.scope != scopeKey) { memory.forget(); memory.scope = scopeKey }
        session?.reset(); spatial.reset(); tapped = null
    }
    val feedback = session?.state?.collectAsState()?.value ?: LiveCameraState()
    val target = feedback.target
    // The confirmed part is remembered beyond this AR session, so it can be found again after the shutter or a pause.
    LaunchedEffect(target?.id, target?.state) {
        if (target != null && target.state != TargetState.Lost) memory.hold(target.concept, target.label)
        if (target?.state == TargetState.Tracking && !target.ambiguous) tapped = null
    }
    // Registration: the port layout is kept on this phone per fixture and asset; marks made in AR are saved as they come.
    val layouts = remember { AssetLayoutStore(File(context.filesDir, "layouts")) }
    SideEffect { spatial.subject = registrationSubject }
    LaunchedEffect(registrationSubject?.fixtureId, registrationSubject?.assetId) {
        spatial.layout = registrationSubject?.let { s -> withContext(Dispatchers.IO) { layouts.load(s.fixtureId, s.assetId) } }
    }
    val registered = feedback.registration
    LaunchedEffect(registered?.layout) { registered?.layout?.let { l -> withContext(Dispatchers.IO) { runCatching { layouts.save(l) } } } }
    // While marking, a tap marks the chosen port; while checking, a tap says where the step's port really is.
    var markLabel by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    // The latest advice as spoken (no distance numbers), local hints and tracking-loss advice included, for the voice
    // pacer, which speaks it through its SpokenFeedbackGate; cleared while live assist is off.
    LaunchedEffect(feedback.spoken, liveEnabled) { onGuidance(if (liveEnabled) feedback.spoken else "") }
    DisposableEffect(session) { onDispose { session?.close() } }
    DisposableEffect(controller, session, arActive) {
        if (session != null && !arActive) controller.setImageAnalysisAnalyzer(executor) { image ->
            try {
                val raw = image.toBitmap()
                val crop = image.cropRect
                val cropped = Bitmap.createBitmap(raw, crop.left, crop.top, crop.width(), crop.height())
                val rotated = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }, true)
                val scale = min(1.0, 640.0 / maxOf(rotated.width, rotated.height))
                val preview = Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt(), (rotated.height * scale).toInt(), true)
                session.frame(preview)
                if (preview !== rotated) preview.recycle()
                if (rotated !== cropped) rotated.recycle()
                if (cropped !== raw) cropped.recycle()
                raw.recycle()
            } finally { image.close() }
        }
        onDispose { controller.clearImageAnalysisAnalyzer() }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> liveEnabled = false
                // Back from the Google Play Services for AR install.
                Lifecycle.Event.ON_RESUME -> arAvailability = ArCore.availability(context)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); controller.unbind(); executor.shutdown() }
    }
    val glass = Color(0xB30B0F14)
    val shown = remember(feedback.objects, targetConcept) { partsToShow(feedback.objects, targetConcept) }
    LaunchedEffect(feedback.objects) { onObjects(feedback.objects) }
    // The options for a tapped part last 8 s, and go sooner when the part is no longer drawn (matched by id, or by
    // concept where the server numbers each detection afresh).
    LaunchedEffect(tapNumber) { if (tapped != null) { delay(8_000); tapped = null } }
    val visiblePick = tapped?.let { visibleSelection(it, shown.first) }
    LaunchedEffect(visiblePick, tapped) { tapped = visiblePick }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val screenHeight = maxHeight
        if (arActive && session != null) ArCameraFeed(session, spatial) { arFailure = it }
        else AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // The before photo over the view, scaled like the feed under it: ARCore fills the view, CameraX letterboxes.
        val referenceImage = remember(reference) { reference?.asImageBitmap() }
        if (referenceImage != null && ghost) Image(referenceImage, null, Modifier.fillMaxSize().alpha(0.35f), contentScale = if (arActive) ContentScale.Crop else ContentScale.Fit)
        // A tap on the picture hides the guidance panels for a clear view; another tap brings them back.
        // A tap on a drawn part (2D or AR) offers to ask about it and, in AR, to hold it as the target; while setting the
        // floor a tap marks it.
        val tapState by rememberUpdatedState(TapState(arActive && feedback.viewSpace, shown.first, pickingFloor, markLabel, checking, feedback.viewSpace, feedback.width, feedback.height, onAskPart != null))
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { at ->
                val st = tapState
                val hit = tappedPart(st.parts, at.x, at.y, size.width.toFloat(), size.height.toFloat(), st.viewSpace, st.frameWidth, st.frameHeight)
                val mark = st.markLabel
                when {
                    st.floorPick -> { spatial.tapFloor(at.x, at.y); pickingFloor = false }
                    st.ar && mark != null -> spatial.markPort(at.x, at.y, mark)
                    st.ar && st.checking -> spatial.checkPort(at.x, at.y)
                    // A box is tapped only when something can be done with it: a question, or holding an anchored part as the
                    // target. Elsewhere (the inspection camera, an unanchored AR box) the tap clears the chrome as before.
                    hit != null && (st.askable || (st.ar && hit.anchorId != null)) -> { tapped = hit; tapNumber++ }
                    else -> hideChrome = !hideChrome
                }
            }
        })

        if (liveEnabled && (feedback.viewSpace || feedback.width > 0)) Canvas(Modifier.fillMaxSize().semantics { contentDescription = shown.first.joinToString(". ") { (if (it.target) "Selected equipment: " else "Camera suggestion: ") + it.label + (it.seenSecondsAgo?.let { s -> ", last seen $s s ago; confirm again" } ?: if (it.uncertain) ", last seen; confirm again" else "") } }) {
            // ARCore draws the camera across the whole view; CameraX letterboxes the image inside it.
            val scale = if (feedback.viewSpace) 1f else min(size.width / feedback.width, size.height / feedback.height)
            val w = if (feedback.viewSpace) size.width else feedback.width * scale
            val h = if (feedback.viewSpace) size.height else feedback.height * scale
            val ox = (size.width - w) / 2; val oy = (size.height - h) / 2
            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(16f, 12f))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 13.sp.toPx() }
            // At most two parts are drawn, the step's own first; the rest are counted in a note, not stacked on the view.
            for (part in shown.first) {
                val b = part.box; val x = ox + (b.left * w).toFloat(); val y = oy + (b.top * h).toFloat()
                val bw = ((b.right - b.left) * w).toFloat(); val bh = ((b.bottom - b.top) * h).toFloat()
                // A box held only by its world anchor (no live 2D track) fades with its age (staleAlpha) and says how long
                // ago it was seen; a target that is uncertain is drawn fainter. The confirmed target has a solid outline.
                val anchorOnly = part.seenSecondsAgo != null || part.uncertain
                val fade = part.seenSecondsAgo?.let(::staleAlpha) ?: if (part.uncertain) 0.6f else 1f
                val tone = FoDark.Primary.copy(alpha = fade)
                val dim = if (part.seenSecondsAgo != null) fade / 0.6f else 1f
                drawRect(FoDark.Primary.copy(alpha = (if (anchorOnly) 0.04f else 0.08f) * dim), Offset(x, y), Size(bw, bh))
                drawRect(FoDark.Primary.copy(alpha = (if (part.target) 0.9f else 0.55f) * dim), Offset(x, y), Size(bw, bh),
                    style = Stroke((if (part.target) 2.5 else 1.5).dp.toPx(), pathEffect = if (part.target) null else dash))
                val c = maxOf(12.dp.toPx(), min(bw, bh) * 0.22f)
                val strokeW = 3.dp.toPx()
                listOf(
                    Offset(x, y) to Offset(1f, 1f),
                    Offset(x + bw, y) to Offset(-1f, 1f),
                    Offset(x, y + bh) to Offset(1f, -1f),
                    Offset(x + bw, y + bh) to Offset(-1f, -1f),
                ).forEach { (o, d) ->
                    drawLine(tone, o, Offset(o.x + d.x * c, o.y), strokeW, StrokeCap.Round)
                    drawLine(tone, o, Offset(o.x, o.y + d.y * c), strokeW, StrokeCap.Round)
                }
                // A short label on a dark pill, above the part (or below it near the top edge), never across it.
                // Distances are estimates (the median depth in the box) and are left off while the target is uncertain.
                val text = (if (part.target) "◎ " else "") + part.label.replaceFirstChar { it.uppercase() } +
                    part.distanceCm?.takeUnless { part.uncertain }?.let { " · est. ${metres(it)} away" }.orEmpty() +
                    part.heightAboveFloorCm?.let { " · ${metres(it)} up" }.orEmpty() + part.seenSecondsAgo?.let { " · last seen $it s ago" }.orEmpty()
                val pad = 6.dp.toPx(); val tw = paint.measureText(text); val th = paint.textSize
                val above = y - pad * 2 - th >= oy + 4.dp.toPx()
                val ty = if (above) y - pad * 2 else y + bh + pad + th
                val tx = (x).coerceIn(ox + pad, maxOf(ox + pad, ox + w - tw - pad * 3))
                drawRoundRect(Color.Black.copy(alpha = 0.62f), Offset(tx - pad, ty - th - pad / 2), Size(tw + pad * 2, th + pad * 1.5f), androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
                drawContext.canvas.nativeCanvas.drawText(text, tx, ty, paint)
            }
        }

        // Framing aid is replaced by tracked boxes as soon as detection locks onto a part.
        if (feedback.objects.isEmpty()) Canvas(Modifier.align(Alignment.Center).offset(y = (-40).dp).size(96.dp)) {
            val l = 18.dp.toPx(); val w = 2.dp.toPx(); val c = FoDark.Foreground.copy(alpha = 0.85f)
            listOf(Offset(0f, 0f) to Offset(1f, 1f), Offset(size.width, 0f) to Offset(-1f, 1f), Offset(0f, size.height) to Offset(1f, -1f), Offset(size.width, size.height) to Offset(-1f, -1f)).forEach { (o, d) ->
                drawLine(c, o, Offset(o.x + d.x * l, o.y), w, StrokeCap.Round)
                drawLine(c, o, Offset(o.x, o.y + d.y * l), w, StrokeCap.Round)
            }
            drawCircle(FoDark.Primary, 2.5.dp.toPx())
        }

        Row(
            Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onClose, modifier = Modifier.clip(CircleShape).background(glass)) { Icon(Icons.Outlined.Close, "Close camera", tint = FoDark.Foreground) }
            // Two lines, then an ellipsis: on a phone the status chip beside it leaves little room, and one clipped line
            // cut the step's name off entirely ("Step 2 of").
            if (header != null) Text(header, color = FoDark.Foreground, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).clip(RoundedCornerShape(50)).background(glass).padding(horizontal = 12.dp, vertical = 8.dp))
            else Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(glass).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(7.dp).background(if (liveEnabled) FoDark.Primary else FoDark.Muted, CircleShape))
                Text(
                    when {
                        !liveEnabled -> "Camera ready"
                        arActive -> "Spatial guidance"
                        else -> "Camera assistance"
                    },
                    fontSize = 11.sp, color = FoDark.Foreground,
                )
            }
        }

        // Off screen or behind the camera, an arrow at the edge points the way back to the confirmed part.
        val cue = target?.cue?.takeIf { arActive && target.state != TargetState.Lost }
        if (cue != null) Canvas(Modifier.fillMaxSize()) { edgeArrow(cue, (if (cue.behind) "Turn around · " else "") + target.label.replaceFirstChar { it.uppercase() }) }

        // The registered equipment: a callout on the marker until a port is earned, then the port itself. The
        // technician's own marks show while marking or checking, labelled as theirs.
        if (arActive && registered != null) Canvas(Modifier.fillMaxSize()) {
            val g = registered.guidance
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 12.sp.toPx() }
            fun pill(text: String, x: Float, y: Float) {
                val pad = 6.dp.toPx(); val tw = paint.measureText(text); val th = paint.textSize
                val left = (x - tw / 2).coerceIn(pad, maxOf(pad, size.width - tw - pad)); val base = y.coerceIn(th + pad, size.height - pad)
                drawRoundRect(Color.Black.copy(alpha = 0.62f), Offset(left - pad, base - th - pad / 2), Size(tw + pad * 2, th + pad * 1.5f), androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
                drawContext.canvas.nativeCanvas.drawText(text, left, base, paint)
            }
            val name = registrationSubject?.assetName.orEmpty()
            if (g.level == GuideLevel.ConfirmAsset || g.level == GuideLevel.Equipment || g.level == GuideLevel.Port) {
                registered.markerAt?.let { m ->
                    val c = Offset((m.x * size.width).toFloat(), (m.y * size.height).toFloat())
                    drawCircle(FoDark.Warning, 18.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                    pill(if (g.level == GuideLevel.ConfirmAsset) "Marker" else "$name · marker", c.x, c.y - 26.dp.toPx())
                }
                if (g.level == GuideLevel.Equipment) registered.markerCue?.let { edgeArrow(it, (if (it.behind) "Turn around · " else "") + name, FoDark.Warning) }
            }
            if (markLabel != null || checking) registered.marks.forEach { (label, p) ->
                val c = Offset((p.x * size.width).toFloat(), (p.y * size.height).toFloat())
                drawCircle(FoDark.Foreground, 4.dp.toPx(), c)
                pill("$label · your mark", c.x, c.y - 12.dp.toPx())
            }
            val port = registrationSubject?.port
            if (g.level == GuideLevel.Port && port != null) {
                registered.portAt?.let { p ->
                    val c = Offset((p.x * size.width).toFloat(), (p.y * size.height).toFloat())
                    drawCircle(FoDark.Primary, 14.dp.toPx(), c, style = Stroke(3.dp.toPx()))
                    drawCircle(FoDark.Primary, 3.dp.toPx(), c)
                    pill("Port $port", c.x, c.y - 24.dp.toPx())
                }
                registered.portCue?.let { edgeArrow(it, (if (it.behind) "Turn around · " else "") + "Port $port") }
            }
        }

        // Below the header: what to do with a tapped part, or the target's state and the floor control while AR runs.
        val pick = visiblePick
        if (pick != null) Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 64.dp, start = 12.dp, end = 12.dp).fillMaxWidth(0.85f)) {
            PartChips(pick,
                onAsk = onAskPart?.let { ask -> { ask(pick); tapped = null } },
                onHold = pick.anchorId?.takeIf { arActive }?.let { anchor -> { spatial.confirm(anchor); tapped = null } },
                onCancel = { tapped = null })
        }
        else if (arActive && (!hideChrome || target?.state == TargetState.Lost || registered?.guidance?.level == GuideLevel.Refused)) Column(
            Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 64.dp, start = 12.dp, end = 12.dp).fillMaxWidth(0.7f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            targetNote(target, memory.label, feedback.objects.any { it.anchorId != null })?.takeUnless { target?.state == TargetState.Tracking && !target.ambiguous }?.let {
                Text(it, color = if (target?.state == TargetState.Tracking || target == null) FoDark.Foreground else FoDark.Warning, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(glass).padding(horizontal = 12.dp, vertical = 6.dp))
            }
            // Registration: the asset question, the guidance for the step's port, and marking and checking ports.
            if (registered != null && registrationSubject != null) {
                val g = registered.guidance
                Column(Modifier.clip(RoundedCornerShape(16.dp)).background(glass).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(TechnicianText.guidance(g.text), fontSize = 12.sp, lineHeight = 16.sp,
                        color = when (g.level) { GuideLevel.Refused -> FoDark.Warning; GuideLevel.Port -> FoDark.Primary; else -> FoDark.Foreground })
                    when (g.level) {
                        GuideLevel.ConfirmAsset -> Row {
                            TextButton(onClick = { spatial.answerAsset(AssetAnswer.Yes) }) { Text("Yes", color = FoDark.Primary, fontSize = 12.sp) }
                            TextButton(onClick = { spatial.answerAsset(AssetAnswer.No) }) { Text("No", color = FoDark.Muted, fontSize = 12.sp) }
                        }
                        GuideLevel.Refused -> TextButton(onClick = { spatial.answerAsset(AssetAnswer.AskAgain) }) { Text("Ask again", color = FoDark.Primary, fontSize = 12.sp) }
                        GuideLevel.Equipment, GuideLevel.Port -> if (registrationSubject.port != null) Row {
                            TextButton(onClick = { checking = false; markLabel = if (markLabel == null) registrationSubject.port else null }) {
                                Text(if (markLabel != null) "Done marking" else "Mark ports", color = FoDark.Primary, fontSize = 12.sp)
                            }
                            if (registered.layout?.point(registrationSubject.port) != null) TextButton(onClick = { markLabel = null; checking = !checking }) {
                                Text(if (checking) "Done checking" else "Check", color = FoDark.Primary, fontSize = 12.sp)
                            }
                        }
                        GuideLevel.FindMarker -> Unit
                    }
                    markLabel?.let { current ->
                        Text("Pick a port, then tap it in the picture. Mark the ports either side too.", fontSize = 11.sp, color = FoDark.Muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            registrationSubject.markable.forEach { label ->
                                val marked = registered.layout?.point(label) != null
                                Text(label + if (marked) " ✓" else "", fontSize = 12.sp, color = if (label == current) FoDark.Background else FoDark.Foreground,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (label == current) FoDark.Primary else Color.Transparent)
                                        .border(1.dp, FoDark.Primary, RoundedCornerShape(50)).clickable { markLabel = label }.padding(horizontal = 10.dp, vertical = 4.dp))
                            }
                        }
                    }
                    if (checking) Text("Tap where ${registrationSubject.port} really is. LensQi measures how far that is from your mark.", fontSize = 11.sp, color = FoDark.Muted)
                    registered.note?.let { Text(TechnicianText.guidance(it), fontSize = 11.sp, color = FoDark.Muted) }
                }
            }
            val floor = feedback.floor
            if (expanded || pickingFloor) {
            Text(
                when {
                    pickingFloor -> "Tap the floor in the picture · tap here to cancel"
                    floor?.set == true -> "Floor set${floor.cameraHeightCm?.let { " · phone ${metres(it)} up" }.orEmpty()} · tap to clear"
                    else -> "Set floor"
                },
                color = if (pickingFloor) FoDark.Primary else FoDark.Foreground, fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(glass).clickable {
                    when {
                        pickingFloor -> pickingFloor = false
                        floor?.set == true -> spatial.clearFloor()
                        else -> pickingFloor = true
                    }
                }.padding(horizontal = 10.dp, vertical = 4.dp),
            )
            floor?.note?.let { Text(it, color = FoDark.Muted, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(glass).padding(horizontal = 10.dp, vertical = 4.dp)) }
            }
        }

        // Kept below the header; when the panels are taller than the room left, the details scroll and the controls stay.
        if (LocalDiagnostics.current && shown.second > 0 && !hideChrome) Text("+${shown.second} more part${if (shown.second == 1) "" else "s"} found", color = FoDark.Foreground, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 64.dp, end = 12.dp).clip(RoundedCornerShape(50)).background(glass).padding(horizontal = 10.dp, vertical = 4.dp))

        // Panels sit in the bottom part of the screen only, so the middle of the picture (where the part usually is)
        // stays clear; the details scroll inside that space and the controls always show.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = screenHeight * 0.58f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          if (!hideChrome || showDetails) {
            if (referenceImage != null) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(glass).clickable { ghost = !ghost }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Image(referenceImage, "Before photo", Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f)) {
                    Text(if (ghost) "Before photo over the view · tap to hide" else "Before photo hidden · tap to show it over the view", fontSize = 12.sp, color = FoDark.Foreground)
                    Text(FRAMING_AID_NOTE, fontSize = 11.sp, lineHeight = 14.sp, color = FoDark.Muted)
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(glass).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (liveEnabled) feedback.status else "Camera assistance paused", color = FoDark.Primary, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (arAvailability) {
                        ArAvailability.Ready -> TextButton(onClick = { spatialEnabled = !spatialEnabled; arFailure = null }) {
                            Text(if (spatialEnabled) "Spatial guidance: On" else "Spatial guidance: Off", color = if (spatialEnabled) FoDark.Primary else FoDark.Muted, fontSize = 11.sp)
                        }
                        ArAvailability.NeedsInstall -> TextButton(onClick = { context.findActivity()?.let { if (ArCore.requestInstall(it)) arAvailability = ArAvailability.Ready } }) {
                            Text("Enable spatial guidance", color = FoDark.Primary, fontSize = 11.sp)
                        }
                        else -> Unit
                    }
                    TextButton(onClick = { liveEnabled = !liveEnabled }, enabled = liveAllowed) {
                        Text(if (!liveAllowed) "Current step only" else if (liveEnabled) "Pause" else "Start assistance", color = if (liveAllowed) FoDark.Foreground else FoDark.Muted)
                    }
                }
                if (LocalDiagnostics.current) Text(
                    "${feedback.renderFps} FPS · ${feedback.fps} updates/s · guidance age ${feedback.guidanceAge ?: "—"}s · ${feedback.tracking.orEmpty()}",
                    fontSize = 11.sp, color = FoDark.Muted)
                if (expanded) Text(
                    if (arActive) "Spatial guidance estimates position and distance. Camera guidance is inferred, not proof of repair. Only shutter photos are saved."
                    else "Guidance is based on camera images; depth is not measured. Images are sent for analysis. Only shutter photos are saved.",
                    fontSize = 12.sp, color = FoDark.Foreground)

            }
            // LensQi's ask: the view the server needs next.
            Row(
                Modifier.fillMaxWidth().clickable(onClickLabel = "Show or hide details") { expanded = !expanded }.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LensQiMark(24)
                Column(Modifier.weight(1f)) {
                    Text(TechnicianText.guidance(if (liveEnabled) feedback.recommendation else prompt), fontSize = 15.sp, lineHeight = 20.sp, color = FoDark.Foreground,
                        maxLines = Int.MAX_VALUE)
                    Text(if (expanded) "Hide details" else "Details", fontSize = 12.sp, color = FoDark.Primary)
                    if (liveEnabled && expanded) feedback.screenText?.let { Text("On screen: “$it”", fontSize = 12.sp, lineHeight = 16.sp, color = FoDark.Muted, maxLines = 3) }
                    (status ?: arFailure)?.let { Text(it, color = FoDark.Failure, fontSize = 13.sp) }
                }
            }
            }
            details?.let { d ->
                if (expanded || showDetails) Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { d() }
                else if (detailsSummary != null) Text("⚠ $detailsSummary",
                    color = if (detailsSummary != null) FoDark.Warning else FoDark.Muted, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(glass).clickable { expanded = true }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
          }
            val shutter = {
                if (!capturing && still == null) {
                    capturing = true
                    status = null
                    stillPart = partToConfirm(capturePart, target)
                    onShutter()
                    liveBeforeShutter = liveEnabled
                    // takePicture before CameraX streams throws; a press during AR or before the preview is up waits for the stream instead.
                    if (arActive || !streaming) pendingCapture = true else takePhoto()
                    // ARCore must hand the camera back for the photo; with CameraX, an embedding screen keeps its live coaching.
                    if (arActive || controls == null) liveEnabled = false
                }
            }
            if (controls != null) controls(CameraHandle(shutter, { session?.latestJpeg() }, capturing, liveEnabled, { liveEnabled = it && liveAllowed }))
            else {
                // The shutter waits for a camera: CameraX streaming, or ARCore holding it (the photo then waits for the hand-back).
                val ready = !capturing && (arActive || streaming)
                Box(
                    Modifier.semantics { contentDescription = if (capturing) "Taking photo" else if (ready) "Take photo" else "Camera starting" }
                        .size(78.dp).border(3.dp, if (ready || capturing) FoDark.Foreground else FoDark.Muted, CircleShape).padding(6.dp).clip(CircleShape)
                        .background(if (capturing) FoDark.Primary else if (ready) FoDark.Foreground else FoDark.Muted.copy(alpha = 0.4f))
                        .clickable(enabled = ready, onClick = shutter),
                    contentAlignment = Alignment.Center,
                ) { if (capturing) CircularProgressIndicator(Modifier.size(28.dp), color = FoDark.Background, strokeWidth = 3.dp) }
            }
        }
        still?.let { path -> StillCheck(path, stillPart.orEmpty(), ::useStill, ::retake) }
    }
}

/**
 * The photo just taken, before it goes anywhere: "Is this the {part}?" on the still itself. The live outline is not
 * carried over, because the preview and the still come from different cameras. Nothing is queued until "Yes".
 */
@Composable
private fun StillCheck(path: String, part: String, onUse: () -> Unit, onRetake: () -> Unit) {
    val image by produceState<Bitmap?>(null, path) { value = withContext(Dispatchers.IO) { runCatching { uprightBitmap(File(path).readBytes(), 1600) }.getOrNull() } }
    // Covers the camera and takes every tap, so the shutter under it can't be pressed again.
    Column(Modifier.fillMaxSize().background(Color.Black).clickable(remember { MutableInteractionSource() }, null) {}.statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            image?.let { Image(it.asImageBitmap(), "The photo just taken", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                ?: CircularProgressIndicator(color = FoDark.Primary)
        }
        Column(
            Modifier.padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color(0xCC0B0F14))
                .border(1.dp, FoDark.Border, RoundedCornerShape(26.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Is this the ${part.lowercase()}?", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, color = FoDark.Foreground)
            Text("Check the photo itself; the outline from the live view isn't carried over. Nothing is uploaded until you choose.", fontSize = 12.sp, lineHeight = 17.sp, color = FoDark.Muted)
            PrimaryButton("Yes, use this photo", onUse, Modifier.fillMaxWidth(), icon = Icons.Outlined.Check)
            SecondaryButton("No, take it again", onRetake, Modifier.fillMaxWidth(), icon = Icons.Outlined.CameraAlt)
        }
    }
}

/** ARCore's camera feed with live assistance. [onFailure] falls back to CameraX 2D tracking with the reason. */
@Composable
private fun ArCameraFeed(live: LiveCameraSession, control: SpatialControl, onFailure: (String) -> Unit) {
    val context = LocalContext.current
    val host = remember(live) {
        runCatching {
            val (session, depth, markers) = ArCore.openSession(context)
            val renderer = ArLiveRenderer(session, depth, live, control, markers) { displayRotation(context) }
            live.onDetection = renderer::onDetection
            val view = GLSurfaceView(context).apply {
                preserveEGLContextOnPause = true
                setEGLContextClientVersion(2)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
            Triple(session, renderer, view)
        }
    }
    val started = host.getOrNull()
    LaunchedEffect(host) {
        if (started == null) { onFailure("Spatial guidance is unavailable. Camera assistance is still available."); return@LaunchedEffect }
        val (session, _, view) = started
        // CameraX releases the camera asynchronously; ARCore can open it once it is free.
        repeat(20) {
            try { session.resume(); view.onResume(); return@LaunchedEffect }
            catch (_: CameraNotAvailableException) { delay(250) }
            // Anything else from ARCore (an unsupported configuration, a missing or stale Play Services for AR, a security
            // refusal) is final: fall back to camera assistance instead of crashing the camera screen.
            catch (e: Exception) { onFailure("Spatial guidance couldn’t start. Continue with camera assistance or reopen the camera."); return@LaunchedEffect }
        }
        onFailure("Spatial guidance couldn’t start. Continue with camera assistance or reopen the camera.")
    }
    if (started == null) return
    DisposableEffect(started) {
        val (session, renderer, view) = started
        onDispose { view.onPause(); session.pause(); renderer.close(); session.close(); live.onDetection = { _, _ -> } }
    }
    AndroidView(factory = { started.third }, modifier = Modifier.fillMaxSize())
}

@Suppress("DEPRECATION")
private fun displayRotation(context: Context): Int = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** "85 cm", or "2.4 m" from a metre up. */
private fun metres(cm: Int): String = if (cm < 100) "$cm cm" else "${"%.1f".format(cm / 100.0)} m"

/** An arrow at the inset edge of the view pointing along [cue], with [text] on a pill just inside it. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.edgeArrow(cue: com.lensqi.fieldops.arcore.EdgeCue, text: String, color: Color = FoDark.Primary) {
    val inset = 40.dp.toPx()
    val tip = edgePoint(cue.dx, cue.dy, size.width, size.height, inset)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE; textSize = 13.sp.toPx() }
    val (tx, ty) = if (tip != null) {
        val (px, py) = tip
        val len = kotlin.math.hypot((px - size.width / 2).toDouble(), (py - size.height / 2).toDouble()).toFloat().coerceAtLeast(1f)
        val ux = (px - size.width / 2) / len; val uy = (py - size.height / 2) / len
        val back = 22.dp.toPx(); val half = 12.dp.toPx()
        drawPath(Path().apply {
            moveTo(px, py)
            lineTo(px - ux * back - uy * half, py - uy * back + ux * half)
            lineTo(px - ux * back + uy * half, py - uy * back - ux * half)
            close()
        }, color)
        (px - ux * (back + 8.dp.toPx())) to (py - uy * (back + 8.dp.toPx()))
    } else size.width / 2 to size.height / 2
    val pad = 6.dp.toPx(); val tw = paint.measureText(text); val th = paint.textSize
    val left = (tx - tw / 2).coerceIn(pad, maxOf(pad, size.width - tw - pad)); val base = (ty + th / 2).coerceIn(th + pad, size.height - pad)
    drawRoundRect(Color.Black.copy(alpha = 0.62f), Offset(left - pad, base - th - pad / 2), Size(tw + pad * 2, th + pad * 1.5f), androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
    drawContext.canvas.nativeCanvas.drawText(text, left, base, paint)
}

private data class TapState(val ar: Boolean, val parts: List<LiveObject>, val floorPick: Boolean, val markLabel: String?, val checking: Boolean,
    val viewSpace: Boolean, val frameWidth: Int, val frameHeight: Int, val askable: Boolean)

/** Rebind only to the same anchor or one unambiguous unanchored part. */
internal fun visibleSelection(tapped: LiveObject, parts: List<LiveObject>): LiveObject? {
    tapped.anchorId?.let { anchor -> return parts.firstOrNull { it.anchorId == anchor } }
    val unanchored = parts.filter { it.anchorId == null }
    // An id is "<prompt position>-<object>", and prompt order changes with the focus, so the same id can name another
    // concept in a later batch; the concept must match too.
    return unanchored.firstOrNull { it.id == tapped.id && it.concept == tapped.concept } ?: unanchored.singleOrNull { it.concept == tapped.concept }
}

/**
 * The drawn part under a tap at ([x], [y]) in a view [viewW] by [viewH], or null. Boxes are fractions of the camera
 * image: in AR ([viewSpace]) the image fills the view; with CameraX a [frameWidth] by [frameHeight] image is letterboxed
 * inside it, the same mapping the canvas draws the outlines with, so a tap lands on the box the technician sees.
 */
internal fun tappedPart(parts: List<LiveObject>, x: Float, y: Float, viewW: Float, viewH: Float, viewSpace: Boolean, frameWidth: Int, frameHeight: Int): LiveObject? {
    if (viewW <= 0f || viewH <= 0f) return null
    val (u, v) = if (viewSpace || frameWidth <= 0 || frameHeight <= 0) (x / viewW).toDouble() to (y / viewH).toDouble()
    else {
        val scale = min(viewW / frameWidth, viewH / frameHeight)
        val w = frameWidth * scale; val h = frameHeight * scale
        ((x - (viewW - w) / 2) / w).toDouble() to ((y - (viewH - h) / 2) / h).toDouble()
    }
    return parts.firstOrNull { u in it.box.left..it.box.right && v in it.box.top..it.box.bottom }
}

/** What to do with a tapped part: ask LensQi about it, hold it as the AR target, or nothing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PartChips(part: LiveObject, onAsk: (() -> Unit)?, onHold: (() -> Unit)?, onCancel: () -> Unit) {
    val name = part.label.lowercase()
    FlowRow(Modifier.semantics { contentDescription = "Options for the $name" }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        onAsk?.let { PartChip("Ask about the $name", FoDark.Primary, it) }
        onHold?.let { PartChip("Hold as target", FoDark.Foreground, it) }
        PartChip("Cancel", FoDark.Muted, onCancel)
    }
}

@Composable
private fun PartChip(text: String, color: Color, onClick: () -> Unit) =
    Text(text, color = color, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xB30B0F14))
        .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp))
