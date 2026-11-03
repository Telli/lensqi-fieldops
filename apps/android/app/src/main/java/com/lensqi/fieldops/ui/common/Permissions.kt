package com.lensqi.fieldops.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun hasPermission(context: Context, permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * True after a request was refused and Android will not ask again (the rationale flag is off while the permission is
 * missing). Only meaningful after the app has asked once: before the first request the flag is off too.
 */
fun permanentlyDenied(context: Context, permission: String): Boolean =
    !hasPermission(context, permission) && (context.findActivity()?.let { !ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } ?: false)

/** Whether Android would still explain a request for [permission]: on after one refusal, off before any and after the last. */
fun rationaleShown(context: Context, permission: String): Boolean =
    context.findActivity()?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } ?: false

/**
 * Whether a refusal just returned was the last one Android will ask about. The rationale flag alone cannot tell: it is
 * off before the first request and off again after a prompt dismissed with Back, so "will not ask again" would be
 * shown wrongly. Only a flag that was on before the request and off after it means Android stopped asking.
 */
fun deniedForGood(rationaleBefore: Boolean, rationaleAfter: Boolean): Boolean = rationaleBefore && !rationaleAfter

/** The app's page in Android Settings, where a permission Android no longer asks about can be allowed. */
fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Shown only once a request was refused for good: what is off, what that costs, and the one tap that can turn it on.
 * [asked] is whether this screen has requested the permission already, so a first visit is never told it was refused.
 */
@Composable
fun PermissionSettingsHint(context: Context, permission: String, asked: Boolean, what: String, consequence: String) {
    if (!asked || !permanentlyDenied(context, permission)) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$what is off for LensQi and Android will not ask again. $consequence", style = MaterialTheme.typography.bodySmall)
        SecondaryButton("Open settings", { openAppSettings(context) })
    }
}
