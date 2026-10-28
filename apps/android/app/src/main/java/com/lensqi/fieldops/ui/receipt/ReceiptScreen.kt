package com.lensqi.fieldops.ui.receipt

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
fun ReceiptScreen(url: String, onBack: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    val background = Fo.Background.toArgb()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("FieldProof receipt", style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider(color = Fo.Border)
        error?.let { Text("Receipt could not load: $it", color = Fo.Failure, modifier = Modifier.padding(16.dp)) }
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(background)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    val origin = android.net.Uri.parse(url).let { "${it.scheme}://${it.authority}" }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                            !request.url.toString().startsWith(origin)
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, e: WebResourceError) {
                            if (request.isForMainFrame) error = "${e.errorCode} ${e.description}"
                        }
                    }
                    loadUrl(url)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
