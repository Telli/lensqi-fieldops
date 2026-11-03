package com.lensqi.fieldops.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lensqi.fieldops.ui.common.PermissionSettingsHint
import com.lensqi.fieldops.ui.common.PrimaryButton
import com.lensqi.fieldops.ui.common.SecondaryButton
import com.lensqi.fieldops.ui.common.hasPermission
import com.lensqi.fieldops.ui.theme.Fo

/**
 * Scans the asset label (QR, Data Matrix or Code 128, or the printed id) and matches it to an asset on a site record; the
 * inspection is created only for an asset the site record lists, and only when the technician taps Inspect. Typing the
 * id uses the same rule. Decoding runs on the phone with the bundled ML Kit models ([AssetScanner]); no frame leaves the
 * device.
 */
@Composable
fun ScanAssetScreen(vm: HomeViewModel, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasPermission(context, Manifest.permission.CAMERA)) }
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    // Back from Settings: what was allowed there takes effect without reopening the screen.
    LifecycleResumeEffect(Unit) { granted = hasPermission(context, Manifest.permission.CAMERA); onPauseOrDispose { } }
    // What the scanner read last (a code or a printed line), and the asset it matched once a read named one; the scanner
    // matches a label read before the site records arrived once they do.
    var payload by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<ScannedAsset?>(null) }
    var typed by remember { mutableStateOf("") }
    val typedMatch = remember(typed, s.fixtures) { assetFromScan(typed, s.fixtures) }

    @Composable
    fun inspect(a: ScannedAsset) = PrimaryButton(if (s.creating == a.assetId) "Starting…" else "Inspect ${a.displayName}", { vm.create(a.fixtureId, a.assetId, onCreated) },
        Modifier.fillMaxWidth(), enabled = s.creating == null, busy = s.creating == a.assetId, icon = Icons.Outlined.QrCodeScanner)

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
                PermissionSettingsHint(context, Manifest.permission.CAMERA, asked, "Camera access", "Without it labels cannot be scanned; type the asset id or pick the asset from the list instead.")
                TypedAssetField(typed, { typed = it }, typedMatch) { inspect(it) }
                s.error?.let { Text(it, color = Fo.Failure, style = MaterialTheme.typography.bodySmall) }
                SecondaryButton("Pick from the list", onBack, Modifier.fillMaxWidth())
                return@Column
            }
            Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(20.dp))) {
                AssetScanner(s.fixtures, Modifier.fillMaxSize(), onRead = { payload = it }, onMatched = { found = it })
            }
            ScanReadout(payload, found, s.fixtures.isEmpty()) { found?.let { inspect(it) } }
            TypedAssetField(typed, { typed = it }, typedMatch) { inspect(it) }
            s.error?.let { Text(it, color = Fo.Failure, style = MaterialTheme.typography.bodySmall) }
            SecondaryButton("Pick from the list instead", onBack, Modifier.fillMaxWidth())
        }
    }
}
