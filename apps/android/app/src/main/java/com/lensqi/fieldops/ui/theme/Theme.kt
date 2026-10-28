package com.lensqi.fieldops.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Provenance colours shared by every screen: evidence, inferred, measured, documented, and the simulated badge. */
object Provenance {
    val Evidence = Color(0xFF2E7D32)
    val Inferred = Color(0xFF6A1B9A)
    val Measured = Color(0xFF1565C0)
    val Documented = Color(0xFF5D4037)
    val Simulated = Color(0xFFE65100)
    val Denied = Color(0xFFC62828)
}

@Composable
fun FieldOpsTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF90CAF9)) else lightColorScheme(primary = Color(0xFF0D47A1))
    MaterialTheme(colorScheme = scheme, content = content)
}
