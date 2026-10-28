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

/**
 * User-triggered capture of a full-resolution JPEG (no downscaling, no filters). The file is handed to the queue,
 * which hashes it on device and uploads the original bytes with source=Phone. [prompt] is the view the server asked for.
 */
@Composable
fun CameraScreen(prompt: String, onClose: () -> Unit, onCaptured: (File) -> Unit) = ForceDark { CameraContent(prompt, onClose, onCaptured) }

@Composable
private fun CameraContent(prompt: String, onClose: () -> Unit, onCaptured: (File) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var status by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }
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
            bindToLifecycle(lifecycleOwner)
        }
    }
    val glass = Color(0xB30B0F14)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { PreviewView(it).apply { this.controller = controller } }, modifier = Modifier.fillMaxSize())

        // Reticle: four corners and a centre dot. It frames the shot; nothing is detected live.
        Canvas(Modifier.align(Alignment.Center).offset(y = (-40).dp).size(96.dp)) {
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
                Box(Modifier.size(7.dp).background(FoDark.Failure, CircleShape))
                Text("Full resolution · saved on this phone first", fontSize = 11.sp, color = FoDark.Foreground)
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            // LensQi's ask: the view the server needs next.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(glass).border(1.dp, FoDark.Border, RoundedCornerShape(18.dp)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LensQiMark(24)
                Column(Modifier.weight(1f)) {
                    Text(prompt, fontSize = 15.sp, lineHeight = 20.sp, color = FoDark.Foreground)
                    status?.let { Text(it, color = FoDark.Failure, fontSize = 13.sp) }
                }
            }
            Box(
                Modifier.size(78.dp).border(3.dp, FoDark.Foreground, CircleShape).padding(6.dp).clip(CircleShape)
                    .background(if (capturing) FoDark.Primary else FoDark.Foreground)
                    .clickable(enabled = !capturing) {
                        capturing = true
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
