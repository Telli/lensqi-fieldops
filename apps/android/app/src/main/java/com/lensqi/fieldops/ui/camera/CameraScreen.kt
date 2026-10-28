package com.lensqi.fieldops.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
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
import com.lensqi.fieldops.ui.theme.Eyebrow
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

        Row(
            Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(glass).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close camera", tint = FoDark.Foreground) }
            Text("Capture evidence", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(end = 12.dp)) {
                Box(Modifier.size(7.dp).background(FoDark.Failure, CircleShape))
                Text("Full resolution", fontSize = 11.sp, color = FoDark.Muted)
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(glass).border(1.dp, FoDark.Primary.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("WHAT TO CAPTURE", style = Eyebrow.copy(color = FoDark.Primary))
                Text(prompt, fontSize = 15.sp)
                status?.let { Text(it, color = FoDark.Failure, fontSize = 13.sp) }
            }
            Box(
                Modifier.size(78.dp).border(3.dp, FoDark.Foreground, CircleShape).padding(6.dp).clip(CircleShape)
                    .background(if (capturing) FoDark.Muted else FoDark.Foreground)
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
            Text("Saved on this phone first, then uploaded unchanged.", fontSize = 12.sp, color = FoDark.Muted)
        }
    }
}
