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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import com.lensqi.fieldops.arcore.ArCoreCapabilityChecker
import com.lensqi.fieldops.arcore.Pose6DoF
import com.lensqi.fieldops.domain.live.LiveResponse
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * User-triggered capture of a full-resolution JPEG (no downscaling, no filters). The file is handed to the queue,
 * which hashes it on device and uploads the original bytes with source=Phone. [prompt] is the view the server asked for.
 */
@Composable
fun CameraScreen(prompt: String, analyzeLive: suspend (String, ByteArray, String) -> LiveResponse, onClose: () -> Unit, onCaptured: (File) -> Unit) = ForceDark { CameraContent(prompt, analyzeLive, onClose, onCaptured) }

@Composable
private fun CameraContent(prompt: String, analyzeLive: suspend (String, ByteArray, String) -> LiveResponse, onClose: () -> Unit, onCaptured: (File) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var status by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var liveEnabled by remember { mutableStateOf(false) }
    val arCapability = remember(context) { ArCoreCapabilityChecker.check(context) }
    var spatialEnabled by remember { mutableStateOf(true) }
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
            bindToLifecycle(lifecycleOwner)
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val session = remember(liveEnabled) { if (liveEnabled) LiveCameraSession(spatialMode = spatialEnabled, analyze = analyzeLive) else null }
    LaunchedEffect(session, spatialEnabled) { session?.spatialMode = spatialEnabled }
    val feedback = session?.state?.collectAsState()?.value ?: LiveCameraState()
    DisposableEffect(context, session, spatialEnabled) {
        val sensorManager = context.getSystemService(android.content.Context.SENSOR_SERVICE) as? SensorManager
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = if (session != null && spatialEnabled && rotationSensor != null) {
            object : SensorEventListener {
                private val quat = FloatArray(4)
                override fun onSensorChanged(event: SensorEvent) {
                    SensorManager.getQuaternionFromVector(quat, event.values)
                    // SensorManager quaternion is [w, x, y, z].
                    session.updatePose(Pose6DoF(qx = quat[1].toDouble(), qy = quat[2].toDouble(), qz = quat[3].toDouble(), qw = quat[0].toDouble()))
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }.also { sensorManager?.registerListener(it, rotationSensor, SensorManager.SENSOR_DELAY_GAME) }
        } else null
        onDispose { if (listener != null) sensorManager?.unregisterListener(listener) }
    }
    DisposableEffect(controller, session) {
        if (session != null) controller.setImageAnalysisAnalyzer(executor) { image ->
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
        onDispose { controller.clearImageAnalysisAnalyzer(); session?.close() }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) liveEnabled = false }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); controller.unbind(); executor.shutdown() }
    }
    val glass = Color(0xB30B0F14)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { PreviewView(it).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; this.controller = controller } }, modifier = Modifier.fillMaxSize())

        if (liveEnabled && feedback.width > 0) Canvas(Modifier.fillMaxSize()) {
            val scale = min(size.width / feedback.width, size.height / feedback.height)
            val w = feedback.width * scale; val h = feedback.height * scale
            val ox = (size.width - w) / 2; val oy = (size.height - h) / 2
            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(16f, 12f))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 14.sp.toPx(); setShadowLayer(5f, 0f, 0f, android.graphics.Color.BLACK) }
            for ((n, part) in feedback.objects.withIndex()) {
                val b = part.box; val x = ox + (b.left * w).toFloat(); val y = oy + (b.top * h).toFloat()
                val bw = ((b.right - b.left) * w).toFloat(); val bh = ((b.bottom - b.top) * h).toFloat()
                drawRect(FoDark.Primary.copy(alpha = 0.08f), Offset(x, y), Size(bw, bh))
                drawRect(FoDark.Primary.copy(alpha = 0.55f), Offset(x, y), Size(bw, bh), style = Stroke(1.5.dp.toPx(), pathEffect = dash))
                val c = maxOf(12.dp.toPx(), min(bw, bh) * 0.22f)
                val strokeW = 3.dp.toPx()
                listOf(
                    Offset(x, y) to Offset(1f, 1f),
                    Offset(x + bw, y) to Offset(-1f, 1f),
                    Offset(x, y + bh) to Offset(1f, -1f),
                    Offset(x + bw, y + bh) to Offset(-1f, -1f),
                ).forEach { (o, d) ->
                    drawLine(FoDark.Primary, o, Offset(o.x + d.x * c, o.y), strokeW, StrokeCap.Round)
                    drawLine(FoDark.Primary, o, Offset(o.x, o.y + d.y * c), strokeW, StrokeCap.Round)
                }
                val distBadge = part.distanceCm?.let { " · ${it} cm" }.orEmpty()
                val anchorBadge = if (part.spatialAnchored) " · 3D" else ""
                drawContext.canvas.nativeCanvas.drawText("${n + 1}. ${part.label}$distBadge$anchorBadge", x + 4.dp.toPx(), maxOf(oy + 18.dp.toPx(), y - 6.dp.toPx()), paint)
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
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(glass).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(7.dp).background(if (liveEnabled && spatialEnabled) FoDark.Primary else FoDark.Failure, CircleShape))
                Text(
                    when {
                        !liveEnabled -> "Camera ready"
                        spatialEnabled && arCapability.available -> "Live 3D AR · ARCore depth"
                        spatialEnabled -> "Live 3D spatial anchor · 6DoF"
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
                    TextButton(onClick = { spatialEnabled = !spatialEnabled }) {
                        Text(if (spatialEnabled) "3D Anchor: ON" else "2D Mode", color = if (spatialEnabled) FoDark.Primary else FoDark.Muted, fontSize = 11.sp)
                    }
                    TextButton(onClick = { liveEnabled = !liveEnabled }) { Text(if (liveEnabled) "Pause live assist" else "Start live assist", color = FoDark.Foreground) }
                }
                Text(if (liveEnabled) "${feedback.fps} tracking updates/s · AI guidance ${feedback.guidanceAge?.let { "from ${it}s ago" } ?: "updating"}"
                    else "Sampled frames go to AI for guidance. Only shutter photos are saved to the inspection.", fontSize = 11.sp, color = FoDark.Muted)
            }
            // LensQi's ask: the view the server needs next.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(glass).border(1.dp, FoDark.Border, RoundedCornerShape(18.dp)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LensQiMark(24)
                Column(Modifier.weight(1f)) {
                    Text(if (liveEnabled) feedback.recommendation else prompt, fontSize = 15.sp, lineHeight = 20.sp, color = FoDark.Foreground)
                    status?.let { Text(it, color = FoDark.Failure, fontSize = 13.sp) }
                }
            }
            Box(
                Modifier.size(78.dp).border(3.dp, FoDark.Foreground, CircleShape).padding(6.dp).clip(CircleShape)
                    .background(if (capturing) FoDark.Primary else FoDark.Foreground)
                    .clickable(enabled = !capturing) {
                        capturing = true
                        liveEnabled = false
                        status = null
                        val file = File(context.cacheDir, "capture-${System.currentTimeMillis()}.jpg")
                        controller.takePicture(
                            ImageCapture.OutputFileOptions.Builder(file).build(),
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) = onCaptured(file)
                                override fun onError(exception: ImageCaptureException) { capturing = false; status = "Capture failed: ${exception.message}" }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) { if (capturing) CircularProgressIndicator(Modifier.size(28.dp), color = FoDark.Background, strokeWidth = 3.dp) }
        }
    }
}
