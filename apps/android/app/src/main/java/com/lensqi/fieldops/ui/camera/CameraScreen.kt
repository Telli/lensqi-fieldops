package com.lensqi.fieldops.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
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
) = ForceDark { CameraContent(prompt, analyzeLive, onClose, onCaptured, header, onGuidance, details, controls) }

/** What a screen that embeds the camera can do with it: take the full-resolution photo, or read the latest live frame. */
class CameraHandle(val capture: () -> Unit, val latestFrame: () -> ByteArray?, val capturing: Boolean, val live: Boolean)

@Composable
private fun CameraContent(
    prompt: String, analyzeLive: suspend (String, ByteArray, String) -> LiveResponse, onClose: () -> Unit, onCaptured: (File) -> Unit,
    header: String?, onGuidance: (String) -> Unit, details: (@Composable ColumnScope.() -> Unit)?, controls: (@Composable (CameraHandle) -> Unit)?,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var status by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }
    // Survive rotation: turning the phone must not switch live assist or 3D AR off.
    var liveEnabled by rememberSaveable { mutableStateOf(false) }
    var arAvailability by remember { mutableStateOf(ArCore.availability(context)) }
    LaunchedEffect(arAvailability) { if (arAvailability == ArAvailability.Checking) { delay(250); arAvailability = ArCore.availability(context) } }
    var spatialEnabled by rememberSaveable { mutableStateOf(true) }
    var arFailure by remember { mutableStateOf<String?>(null) }
    var pendingCapture by remember { mutableStateOf(false) }
    val arActive = liveEnabled && spatialEnabled && arAvailability == ArAvailability.Ready && arFailure == null
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
            Text("Camera access is needed to capture evidence. Nothing is recorded without your tap.")
            PrimaryButton("Allow camera", { ask.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth())
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

    fun takePhoto() {
        val file = File(context.cacheDir, "capture-${System.currentTimeMillis()}.jpg")
        controller.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) { capturing = false; onCaptured(file) }
                override fun onError(exception: ImageCaptureException) { capturing = false; status = "Capture failed: ${exception.message}" }
            },
        )
    }
    // A shutter press during AR waits for CameraX, so the evidence photo is always full resolution.
    LaunchedEffect(pendingCapture, streaming, arActive) { if (pendingCapture && streaming && !arActive) { pendingCapture = false; takePhoto() } }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val session = remember(liveEnabled) { if (liveEnabled) LiveCameraSession(analyze = analyzeLive) else null }
    val feedback = session?.state?.collectAsState()?.value ?: LiveCameraState()
    // New AI guidance (its age resets to 0), not the local light and focus hints that change every frame.
    LaunchedEffect(feedback.guidanceAge) { if (liveEnabled && feedback.guidanceAge == 0L) onGuidance(feedback.recommendation) }
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
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (arActive && session != null) ArCameraFeed(session) { arFailure = it }
        else AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        if (liveEnabled && (feedback.viewSpace || feedback.width > 0)) Canvas(Modifier.fillMaxSize()) {
            // ARCore draws the camera across the whole view; CameraX letterboxes the image inside it.
            val scale = if (feedback.viewSpace) 1f else min(size.width / feedback.width, size.height / feedback.height)
            val w = if (feedback.viewSpace) size.width else feedback.width * scale
            val h = if (feedback.viewSpace) size.height else feedback.height * scale
            val ox = (size.width - w) / 2; val oy = (size.height - h) / 2
            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(16f, 12f))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 14.sp.toPx(); setShadowLayer(5f, 0f, 0f, android.graphics.Color.BLACK) }
            for ((n, part) in feedback.objects.withIndex()) {
                val b = part.box; val x = ox + (b.left * w).toFloat(); val y = oy + (b.top * h).toFloat()
                val bw = ((b.right - b.left) * w).toFloat(); val bh = ((b.bottom - b.top) * h).toFloat()
                // A box held only by its world anchor (no live 2D track) is drawn fainter and says when it was last seen.
                val anchorOnly = part.seenSecondsAgo != null
                val tone = if (anchorOnly) FoDark.Primary.copy(alpha = 0.6f) else FoDark.Primary
                drawRect(FoDark.Primary.copy(alpha = if (anchorOnly) 0.04f else 0.08f), Offset(x, y), Size(bw, bh))
                drawRect(FoDark.Primary.copy(alpha = 0.55f), Offset(x, y), Size(bw, bh), style = Stroke(1.5.dp.toPx(), pathEffect = dash))
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
                val distance = part.distanceCm?.let { " · ≈$it cm" }.orEmpty()
                val seen = part.seenSecondsAgo?.let { " · seen ${it}s ago" }.orEmpty()
                drawContext.canvas.nativeCanvas.drawText("${n + 1}. ${part.label}$distance$seen", x + 4.dp.toPx(), maxOf(oy + 18.dp.toPx(), y - 6.dp.toPx()), paint)
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
            if (header != null) Text(header, color = FoDark.Foreground, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f).clip(RoundedCornerShape(50)).background(glass).padding(horizontal = 12.dp, vertical = 8.dp))
            else Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(glass).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(7.dp).background(if (liveEnabled) FoDark.Primary else FoDark.Muted, CircleShape))
                Text(
                    when {
                        !liveEnabled -> "Camera ready"
                        arActive -> feedback.tracking ?: "Live AR · starting"
                        else -> "Live 2D assistance · AI inferred"
                    },
                    fontSize = 11.sp, color = FoDark.Foreground,
                )
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(glass).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (liveEnabled) feedback.status else "Live camera assistance", color = FoDark.Primary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    when (arAvailability) {
                        ArAvailability.Ready -> TextButton(onClick = { spatialEnabled = !spatialEnabled; arFailure = null }) {
                            Text(if (spatialEnabled) "3D AR: On" else "3D AR: Off", color = if (spatialEnabled) FoDark.Primary else FoDark.Muted, fontSize = 11.sp)
                        }
                        ArAvailability.NeedsInstall -> TextButton(onClick = { context.findActivity()?.let { if (ArCore.requestInstall(it)) arAvailability = ArAvailability.Ready } }) {
                            Text("Get 3D AR", color = FoDark.Primary, fontSize = 11.sp)
                        }
                        else -> Unit
                    }
                    TextButton(onClick = { liveEnabled = !liveEnabled }) { Text(if (liveEnabled) "Pause live assist" else "Start live assist", color = FoDark.Foreground) }
                }
                Text(if (liveEnabled) "${feedback.fps} tracking updates/s · AI guidance ${feedback.guidanceAge?.let { "from ${it}s ago" } ?: "updating"}"
                    else if (arAvailability == ArAvailability.Ready && spatialEnabled) "Sampled frames go to AI for guidance; ARCore measures distance on this phone. Only shutter photos are saved."
                    else "Sampled frames go to AI for guidance. Only shutter photos are saved.", fontSize = 11.sp, color = FoDark.Muted)
            }
            // LensQi's ask: the view the server needs next.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(glass).border(1.dp, FoDark.Border, RoundedCornerShape(18.dp)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LensQiMark(24)
                Column(Modifier.weight(1f)) {
                    Text(if (liveEnabled) feedback.recommendation else prompt, fontSize = 15.sp, lineHeight = 20.sp, color = FoDark.Foreground)
                    if (liveEnabled) feedback.screenText?.let { Text("On screen: “$it”", fontSize = 12.sp, lineHeight = 16.sp, color = FoDark.Muted, maxLines = 3) }
                    (status ?: arFailure)?.let { Text(it, color = FoDark.Failure, fontSize = 13.sp) }
                }
            }
            details?.invoke(this)
            val shutter = {
                if (!capturing) {
                    capturing = true
                    status = null
                    if (arActive) pendingCapture = true else takePhoto()
                    // ARCore must hand the camera back for the photo; with CameraX, an embedding screen keeps its live coaching.
                    if (arActive || controls == null) liveEnabled = false
                }
            }
            if (controls != null) controls(CameraHandle(shutter, { session?.latestJpeg() }, capturing, liveEnabled))
            else Box(
                Modifier.size(78.dp).border(3.dp, FoDark.Foreground, CircleShape).padding(6.dp).clip(CircleShape)
                    .background(if (capturing) FoDark.Primary else FoDark.Foreground)
                    .clickable(enabled = !capturing, onClick = shutter),
                contentAlignment = Alignment.Center,
            ) { if (capturing) CircularProgressIndicator(Modifier.size(28.dp), color = FoDark.Background, strokeWidth = 3.dp) }
        }
    }
}

/** ARCore's camera feed with live assistance. [onFailure] falls back to CameraX 2D tracking with the reason. */
@Composable
private fun ArCameraFeed(live: LiveCameraSession, onFailure: (String) -> Unit) {
    val context = LocalContext.current
    val host = remember(live) {
        runCatching {
            val (session, depth) = ArCore.openSession(context)
            val renderer = ArLiveRenderer(session, depth, live) { displayRotation(context) }
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
        if (started == null) { onFailure("3D AR couldn't start (${host.exceptionOrNull()?.javaClass?.simpleName}). Using 2D tracking."); return@LaunchedEffect }
        val (session, _, view) = started
        // CameraX releases the camera asynchronously; ARCore can open it once it is free.
        repeat(20) {
            try { session.resume(); view.onResume(); return@LaunchedEffect } catch (_: CameraNotAvailableException) { delay(250) }
        }
        onFailure("The camera stayed busy, so 3D AR couldn't start. Using 2D tracking.")
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
