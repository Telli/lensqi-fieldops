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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.lensqi.fieldops.data.api.Fixture
import com.lensqi.fieldops.ui.common.Panel
import com.lensqi.fieldops.ui.common.PermissionSettingsHint
import com.lensqi.fieldops.ui.common.PrimaryButton
import com.lensqi.fieldops.ui.common.SecondaryButton
import com.lensqi.fieldops.ui.common.hasPermission
import com.lensqi.fieldops.ui.theme.Fo
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Text recognition runs on at most every [OCR_EVERY_N_FRAMES]th frame and no more often than every [OCR_MIN_GAP_MS] ms, so the preview stays smooth. */
private const val OCR_EVERY_N_FRAMES = 3
private const val OCR_MIN_GAP_MS = 330L

/** The hint shown while nothing has been read yet; the screen and the sheet share it. */
internal const val SCAN_HINT = "Point at the QR code, barcode or printed id on the asset label."
internal const val TYPE_HINT = "Or type the asset id, e.g. LAB-SW-01"
internal const val NOT_AN_ASSET = "not an asset on a site record"

/**
 * The camera preview that reads asset labels: QR, Data Matrix or Code 128 codes with the bundled ML Kit barcode model
 * and, on frames without a code, the printed id with the bundled Latin text model. Everything stays on-device; no frame
 * leaves the phone. Every read is reported through [onRead] (the raw code, or the recognised line most likely to be the
 * id) so a screen can show "Read: …"; [onMatched] fires once, for the first read that names an asset on one of
 * [fixtures], after which later frames are dropped so another label cannot swap the asset under the button. A read that
 * names no asset does not end scanning. A label read before the site records arrived matches once they do.
 *
 * Camera permission is the caller's job: the scanner assumes it is granted.
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
fun AssetScanner(fixtures: List<Fixture>, modifier: Modifier = Modifier, onRead: (String) -> Unit, onMatched: (ScannedAsset) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val fixturesNow = rememberUpdatedState(fixtures)
    val read = rememberUpdatedState(onRead)
    val matched = rememberUpdatedState(onMatched)
    // The first read that names an asset wins, decided on the analyzer executor: Compose state follows a frame later, and
    // two labels in consecutive frames must not both pass the gate with the later one replacing the first.
    val taken = remember { AtomicReference<String?>(null) }
    // The last text reported, so the same label in view is not reported frame after frame, and so it can be matched
    // against site records that arrive after it was read.
    val lastRead = remember { AtomicReference<String?>(null) }
    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        }
    }
    // Site records that arrive after a label was read: the read matches now, and takes the latch like a fresh read would.
    LaunchedEffect(fixtures) {
        val last = lastRead.get() ?: return@LaunchedEffect
        val asset = assetFromScan(last, fixtures) ?: return@LaunchedEffect
        if (taken.compareAndSet(null, last)) matched.value(asset)
    }

    DisposableEffect(controller, owner) {
        val executor = Executors.newSingleThreadExecutor()
        val barcodes = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_CODE_128).build())
        val text = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val framesInFlight = ScanLifetime {
            barcodes.close()
            text.close()
            executor.shutdown()
        }
        val frames = AtomicInteger(0)
        val lastOcr = AtomicLong(0L)
        val main = ContextCompat.getMainExecutor(context)
        // Decides on the analyzer executor whether [shown] is reported and whether [asset] takes the latch; the callbacks run on the main thread.
        fun report(shown: String, asset: ScannedAsset?) {
            if (asset != null && !taken.compareAndSet(null, shown)) return
            val changed = lastRead.getAndSet(shown) != shown
            main.execute {
                if (framesInFlight.disposed) return@execute
                if (changed) read.value(shown)
                if (asset != null) matched.value(asset)
            }
        }
        controller.setImageAnalysisAnalyzer(executor) { proxy ->
            val media = proxy.image
            if (media == null || taken.get() != null || !framesInFlight.begin()) { proxy.close(); return@setImageAnalysisAnalyzer }
            val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
            val frame = frames.incrementAndGet()
            barcodes.process(image)
                .continueWithTask(executor) { task ->
                    val codes = if (task.isSuccessful) task.result.mapNotNull { it.rawValue } else emptyList()
                    val known = matchingRead(codes, fixturesNow.value)
                    val now = System.currentTimeMillis()
                    when {
                        known != null -> { report(known.first, known.second); Tasks.forResult(null) }
                        // No known code in the frame: read the printed id, but not on every frame.
                        frame % OCR_EVERY_N_FRAMES == 0 && now - lastOcr.get() >= OCR_MIN_GAP_MS -> {
                            lastOcr.set(now)
                            text.process(image).continueWith(executor) { ocr ->
                                if (!ocr.isSuccessful) return@continueWith null
                                val lines = ocr.result.textBlocks.flatMap { b -> b.lines.map { it.text } }
                                val fx = fixturesNow.value
                                val match = matchingRead(lines, fx)
                                val shown = match?.first ?: likelyIdLine(lines) ?: codes.firstOrNull()
                                if (shown != null) report(shown, match?.second)
                                null
                            }
                        }
                        else -> { codes.firstOrNull()?.let { report(it, null) }; Tasks.forResult(null) }
                    }
                }
                .addOnCompleteListener { proxy.close(); framesInFlight.finish() }
        }
        controller.bindToLifecycle(owner)
        onDispose { controller.clearImageAnalysisAnalyzer(); controller.unbind(); framesInFlight.dispose() }
    }
    AndroidView(
        factory = { PreviewView(it).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; this.controller = controller } },
        modifier = modifier,
    )
}

/**
 * What the scanner read, as the technician sees it: the matched asset with its site-record line and [action] below it, a
 * read that names no asset with the "Read: …" line (cut to 80 characters), or the hint while nothing has been read.
 */
@Composable
internal fun ScanReadout(read: String?, match: ScannedAsset?, fixturesEmpty: Boolean, action: @Composable () -> Unit) {
    when {
        match != null -> Panel(border = Fo.Primary) {
            Text(match.displayName, fontWeight = FontWeight.SemiBold)
            Text("${match.fixtureId} · ${match.assetId} · from the site record", style = MaterialTheme.typography.bodySmall)
            action()
        }
        read != null -> Panel(border = Fo.Warning) {
            // A code or a printed line: either way, the read is shown so the technician sees what was taken for the id.
            Text(NOT_AN_ASSET.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Medium)
            Text("Read: ${read.take(80)}", style = MaterialTheme.typography.bodySmall)
            Text(if (fixturesEmpty) "No site records are loaded; connect to the server and try again." else "Only assets listed on a site record can be inspected. Pick one from the list if the label is unreadable.", style = MaterialTheme.typography.bodySmall)
        }
        else -> Text(SCAN_HINT, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The typed fallback: the field and the asset the typed id names, by the same rule as a scanned label ([assetFromScan]).
 * [action] draws the button for [typedMatch] below the field; while the field is empty nothing is drawn but the field.
 */
@Composable
internal fun TypedAssetField(typed: String, onTyped: (String) -> Unit, typedMatch: ScannedAsset?, action: @Composable (ScannedAsset) -> Unit) {
    OutlinedTextField(typed, onTyped, label = { Text(TYPE_HINT) }, singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Fo.Primary, unfocusedBorderColor = Fo.Border), modifier = Modifier.fillMaxWidth())
    when {
        typedMatch != null -> action(typedMatch)
        typed.isNotBlank() -> Text("\"${typed.trim().take(80)}\" is $NOT_AN_ASSET.", style = MaterialTheme.typography.bodySmall, color = Fo.Warning)
    }
}

/**
 * A bottom sheet that picks an asset from its label: camera permission, the [AssetScanner], what it read, and the typed
 * fallback. Only an id from the site records can be picked; [onPicked] is called with it when the technician taps Use.
 * Self-contained so the technician's task screen can embed it without the home view model.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetScannerSheet(fixtures: List<Fixture>, onPicked: (ScannedAsset) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasPermission(context, Manifest.permission.CAMERA)) }
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    // Back from Settings: what was allowed there takes effect without reopening the sheet.
    LifecycleResumeEffect(Unit) { granted = hasPermission(context, Manifest.permission.CAMERA); onPauseOrDispose { } }
    var read by remember { mutableStateOf<String?>(null) }
    var scanned by remember { mutableStateOf<ScannedAsset?>(null) }
    var typed by remember { mutableStateOf("") }
    val typedMatch = remember(typed, fixtures) { assetFromScan(typed, fixtures) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Fo.Surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Scan an asset label", style = MaterialTheme.typography.titleMedium)
            if (granted) {
                Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(20.dp))) {
                    AssetScanner(fixtures, Modifier.fillMaxSize(), onRead = { read = it }, onMatched = { scanned = it })
                }
                ScanReadout(read, scanned, fixtures.isEmpty()) {
                    scanned?.let { a -> PrimaryButton("Use ${a.displayName}", { onPicked(a) }, Modifier.fillMaxWidth(), icon = Icons.Outlined.QrCodeScanner) }
                }
            } else {
                Text("Camera access is needed to read the label. Nothing is recorded; the code is matched on this phone.")
                PrimaryButton("Allow camera", { ask.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth())
                PermissionSettingsHint(context, Manifest.permission.CAMERA, asked, "Camera access", "Without it labels cannot be scanned; type the asset id instead.")
            }
            TypedAssetField(typed, { typed = it }, typedMatch) { a -> PrimaryButton("Use ${a.displayName}", { onPicked(a) }, Modifier.fillMaxWidth()) }
            SecondaryButton("Cancel", onDismiss, Modifier.fillMaxWidth())
        }
    }
}
