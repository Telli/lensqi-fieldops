package com.lensqi.fieldops.ui.receipt

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class WebOrigin(val scheme: String, val host: String, val port: Int)
fun webOrigin(url: String): WebOrigin? = url.toHttpUrlOrNull()?.takeIf {
    it.username.isEmpty() && it.password.isEmpty()
}?.let { WebOrigin(it.scheme, it.host, it.port) }
fun sameWebOrigin(trusted: String, candidate: String): Boolean =
    webOrigin(trusted)?.let { it == webOrigin(candidate) } ?: false
