package com.lensqi.fieldops.ui.receipt

import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** The server-rendered receipt.html, shown as-is so the phone and the browser show the same document. */
@Composable
fun ReceiptScreen(url: String) {
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        error?.let { Text("Receipt could not load: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = false
                    webViewClient = object : WebViewClient() {
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
