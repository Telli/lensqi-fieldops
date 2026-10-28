package com.lensqi.fieldops.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The LensQi FieldOps flagship dark palette (same tokens as the web app's design). */
object Fo {
    val Background = Color(0xFF0B0F14)
    val Surface = Color(0xFF131A22)
    val Elevated = Color(0xFF1B242E)
    val Foreground = Color(0xFFF4F7FA)
    val Muted = Color(0xFF9AA8B6)
    val Primary = Color(0xFF35D6E8)
    val OnPrimary = Color(0xFF0B0F14)
    val Accent = Color(0xFF0E2D36)
    val AccentFg = Color(0xFF76E1ED)
    val Success = Color(0xFF3DC28C)
    val Warning = Color(0xFFEEB563)
    val Failure = Color(0xFFE97078)
    val Border = Color(0xFF262F38)
    val Overlay = Color(0xFF05080C)
}

/** Provenance colours kept for the existing call sites; mapped onto the design palette. */
object Provenance {
    val Evidence = Fo.Success
    val Inferred = Fo.Primary
    val Measured = Fo.Primary
    val Documented = Fo.Muted
    val Simulated = Fo.Warning
    val Denied = Fo.Failure
}

private val scheme = darkColorScheme(
    primary = Fo.Primary, onPrimary = Fo.OnPrimary,
    secondary = Fo.Elevated, onSecondary = Fo.Foreground,
    background = Fo.Background, onBackground = Fo.Foreground,
    surface = Fo.Background, onSurface = Fo.Foreground,
    surfaceVariant = Fo.Surface, onSurfaceVariant = Fo.Muted,
    surfaceContainer = Fo.Surface, surfaceContainerHigh = Fo.Elevated, surfaceContainerLow = Fo.Surface,
    outline = Fo.Border, outlineVariant = Fo.Border,
    error = Fo.Failure, onError = Fo.OnPrimary,
)

private val base = Typography()
private val type = base.copy(
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp, color = Fo.Muted),
    labelSmall = base.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

/** Small uppercase section label (the design's "eyebrow"). */
val Eyebrow = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.3.sp, color = Fo.Muted)
val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)

@Composable
fun FieldOpsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
