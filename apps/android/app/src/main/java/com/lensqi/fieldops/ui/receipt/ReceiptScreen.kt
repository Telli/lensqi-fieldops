package com.lensqi.fieldops.ui.receipt

import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** The server-rendered receipt.html, shown as-is so the phone and the browser show the same document. */
@Composable
fun ReceiptScreen(url: String) {
    AndroidView(factory = { WebView(it).apply { settings.javaScriptEnabled = false; loadUrl(url) } }, modifier = Modifier.fillMaxSize())
}
