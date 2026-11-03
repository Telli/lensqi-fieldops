package com.lensqi.fieldops.ui.receipt

import kotlinx.coroutines.launch
import android.annotation.SuppressLint
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import com.lensqi.fieldops.ui.common.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lensqi.fieldops.ui.theme.Fo

/**
 * The receipt page served by the inspection API, so the phone and the browser show the same document.
 * JavaScript is on because the page is the API's own web app; the WebView never loads any other origin's content.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReceiptScreen(url: String, onBack: () -> Unit, vm: RecordViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var exportPath by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val save = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = exportPath?.let { java.io.File(it) }
        if (uri != null && file != null) scope.launch {
            exportMessage = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    // A crashed document provider returns no stream; that is a failed save like any other.
                    (context.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("No output stream for $uri"))
                        .use { output -> file.inputStream().use { it.copyTo(output) } }
                }
                "Record saved"
            } catch (e: java.io.IOException) { "The record could not be saved. Try another location." }
        }
    }
    val id = remember(url) { recordInspectionId(url) }
    LaunchedEffect(id) { vm.load(id) }
    var technical by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val background = Fo.Background.toArgb()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("Inspection record", style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider(color = Fo.Border)
        if (!technical) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading) Text("Loading inspection record…")
            state.error?.let {
                Text(it, color = Fo.Failure)
                SecondaryButton("Try again", { vm.load(id) })
            }
            state.summary?.let { record ->
                Text(record.title, style = MaterialTheme.typography.headlineSmall)
                record.checks.map { it.source }.distinct().forEach { source ->
                    if (source != null) SourceBadge(source) else Text("Check source unavailable", color = Fo.Warning)
                }
                record.reasons.forEach { Text(it) }
                if (record.proves.isNotEmpty()) {
                    Text("What the check proves", style = MaterialTheme.typography.titleSmall)
                    record.proves.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
                Text("${record.photos} evidence photos", style = MaterialTheme.typography.bodyMedium)
                // The record's limits stay on the summary: a verified result means only what these allow.
                if (record.limits.isNotEmpty()) {
                    Text("Limits of this record", style = MaterialTheme.typography.titleSmall)
                    record.limits.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.TextButton(enabled = !state.exporting && id != null, onClick = {
                        scope.launch { vm.export(id!!, java.io.File(context.cacheDir, "receipt-exports"))?.let { file ->
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "application/zip"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                clipData = android.content.ClipData.newRawUri("Inspection record", uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "Share inspection record"))
                        } }
                    }) { Text(if (state.exporting) "Preparing…" else "Share bundle") }
                    androidx.compose.material3.TextButton(enabled = !state.exporting && id != null, onClick = {
                        scope.launch { vm.export(id!!, java.io.File(context.cacheDir, "receipt-exports"))?.let { file ->
                            exportPath = file.path; save.launch("fieldproof-$id.zip")
                        } }
                    }) { Text("Save bundle") }
                }
                exportMessage?.let { Text(it) }
                // An export failure does not unload the record; the summary stays and Try again is not offered for it.
                state.exportError?.let { Text(it, color = Fo.Failure) }
                Expandable("Details") {
                    Text("Check history · earliest first", style = MaterialTheme.typography.titleSmall)
                    Text("Includes earlier attempts. The result above is the recorded final decision.", style = MaterialTheme.typography.bodySmall)
                    record.checks.forEach { check ->
                        Text(TechnicianText.connection(check.passed))
                        Text("Checked ${recordTime(check.at)}", style = MaterialTheme.typography.bodySmall)
                        check.source?.let { SourceBadge(it) }
                    }
                    Text("Record generated ${recordTime(record.generated)}", style = MaterialTheme.typography.bodySmall)
                    Text("The full record retains the original evidence, identifiers and verification details.", style = MaterialTheme.typography.bodySmall)
                    // Each opening loads the page again, so an earlier failure must not outlive it.
                    SecondaryButton("Open technical record", { error = null; technical = true })
                }
            }
        } else {
        androidx.compose.material3.TextButton(onClick = { technical = false }) { Text("Back to summary") }
        error?.let { Text("The technical record couldn’t load. Check your connection, then open it again.", color = Fo.Failure, modifier = Modifier.padding(16.dp)) }
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(background)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                            !sameWebOrigin(url, request.url.toString())
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, e: WebResourceError) {
                            if (request.isForMainFrame) {
                                com.lensqi.fieldops.data.diagnostics.DiagnosticHistory.record("record", "load_failed", e.errorCode)
                                error = "load_failed"
                            }
                        }
                    }
                    loadUrl(url)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        }
    }
}
