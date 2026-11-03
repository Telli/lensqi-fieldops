package com.lensqi.fieldops.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.lensqi.fieldops.ui.common.Panel
import com.lensqi.fieldops.ui.common.PermissionSettingsHint
import com.lensqi.fieldops.ui.common.PrimaryButton
import com.lensqi.fieldops.ui.common.SecondaryButton
import com.lensqi.fieldops.ui.common.hasPermission
import com.lensqi.fieldops.ui.theme.Fo
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scans the asset label (QR, Data Matrix or Code 128) and matches it to an asset on a site record; the inspection is
 * created only for an asset the site record lists, and only when the technician taps Inspect. Decoding runs on the phone
 * with the bundled ML Kit model; no frame leaves the device.
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
fun ScanAssetScreen(vm: HomeViewModel, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(hasPermission(context, Manifest.permission.CAMERA)) }
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    // Back from Settings: what was allowed there takes effect without reopening the screen.
    LifecycleResumeEffect(Unit) { granted = hasPermission(context, Manifest.permission.CAMERA); onPauseOrDispose { } }
    var payload by remember { mutableStateOf<String?>(null) }
    // Matched from the code and the site records as they are now: a label read before the records arrived matches once they do.
    val found = remember(payload, s.fixtures) { payload?.let { assetFromScan(it, s.fixtures) } }
    // Read by the analyzer thread: once a label matched, later frames are dropped so another code cannot swap the asset under the Inspect button.
    val matched = rememberUpdatedState(found)
    val fixturesNow = rememberUpdatedState(s.fixtures)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("Scan an asset label", style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider(color = Fo.Border)
        // The buttons sit below the 3:4 preview; on a short screen they are reached by scrolling.
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!granted) {
                Text("Camera access is needed to read the label. Nothing is recorded; the code is matched on this phone.")
                PrimaryButton("Allow camera", { ask.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth())
                PermissionSettingsHint(context, Manifest.permission.CAMERA, asked, "Camera access", "Without it labels cannot be scanned; pick the asset from the list instead.")
                SecondaryButton("Pick from the list", onBack, Modifier.fillMaxWidth())
                return@Column
            }
            val controller = remember {
                LifecycleCameraController(context).apply {
                    cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
                    imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                }
            }
            val executor = remember { Executors.newSingleThreadExecutor() }
            val scanner = remember {
                BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_CODE_128).build())
            }
            DisposableEffect(controller, owner) {
                val busy = AtomicBoolean(false)
                // The first code that names an asset wins, decided here on the analyzer thread: Compose state follows a frame
                // later, and two labels in consecutive frames must not both pass the gate with the later one replacing the first.
                // A code that names no asset is shown but does not end scanning, so the right label can still be read.
                val taken = java.util.concurrent.atomic.AtomicReference<String?>(null)
                controller.setImageAnalysisAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null || taken.get() != null || matched.value != null || !busy.compareAndSet(false, true)) { proxy.close(); return@setImageAnalysisAnalyzer }
                    scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            codes.firstNotNullOfOrNull { it.rawValue }?.let { raw ->
                                val names = assetFromScan(raw, fixturesNow.value) != null
                                if (!names || taken.compareAndSet(null, raw)) ContextCompat.getMainExecutor(context).execute { if (raw != payload) payload = raw }
                            }
                        }
                        .addOnCompleteListener { proxy.close(); busy.set(false) }
                }
                controller.bindToLifecycle(owner)
                onDispose { controller.clearImageAnalysisAnalyzer(); controller.unbind(); scanner.close(); executor.shutdown() }
            }
            AndroidView(
                factory = { PreviewView(it).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; this.controller = controller } },
                modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(20.dp)),
            )
            val match = found
            when {
                match != null -> Panel(border = Fo.Primary) {
                    Text(match.displayName, fontWeight = FontWeight.SemiBold)
                    Text("${match.fixtureId} · ${match.assetId} · from the site record", style = MaterialTheme.typography.bodySmall)
                    PrimaryButton(if (s.creating == match.assetId) "Starting…" else "Inspect ${match.displayName}", { vm.create(match.fixtureId, match.assetId, onCreated) },
                        Modifier.fillMaxWidth(), enabled = s.creating == null, busy = s.creating == match.assetId, icon = Icons.Outlined.QrCodeScanner)
                }
                payload != null -> Panel(border = Fo.Warning) {
                    Text("This code is not an asset on a site record", fontWeight = FontWeight.Medium)
                    Text("Read: ${payload!!.take(80)}", style = MaterialTheme.typography.bodySmall)
                    Text(if (s.fixtures.isEmpty()) "No site records are loaded; connect to the server and try again." else "Only assets listed on a site record can be inspected. Pick one from the list if the label is unreadable.", style = MaterialTheme.typography.bodySmall)
                }
                else -> Text("Point at the QR or barcode on the asset label.", style = MaterialTheme.typography.bodySmall)
            }
            s.error?.let { Text(it, color = Fo.Failure, style = MaterialTheme.typography.bodySmall) }
            SecondaryButton("Pick from the list instead", onBack, Modifier.fillMaxWidth())
        }
    }
}
