package com.lensqi.fieldops.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** One colour set of the design. Names match the web tokens (background, surface, elevated, foreground, …). */
data class FoPalette(
    val Background: Color, val Surface: Color, val Elevated: Color, val Foreground: Color, val Muted: Color,
    val Primary: Color, val OnPrimary: Color, val Accent: Color, val AccentFg: Color,
    val Success: Color, val Warning: Color, val Failure: Color, val Border: Color, val Overlay: Color, val isDark: Boolean,
)

/** The flagship dark palette. */
val DarkPalette = FoPalette(
    Background = Color(0xFF0B0F14), Surface = Color(0xFF131A22), Elevated = Color(0xFF1B242E), Foreground = Color(0xFFF4F7FA),
    Muted = Color(0xFF9AA8B6), Primary = Color(0xFF35D6E8), OnPrimary = Color(0xFF0B0F14), Accent = Color(0xFF0E2D36),
    AccentFg = Color(0xFF76E1ED), Success = Color(0xFF3DC28C), Warning = Color(0xFFEEB563), Failure = Color(0xFFE97078),
    Border = Color(0xFF262F38), Overlay = Color(0xFF05080C), isDark = true,
)

/** The design's light palette, converted from the web app's OKLCH tokens. */
val LightPalette = FoPalette(
    Background = Color(0xFFF4F7F9), Surface = Color(0xFFEDF1F4), Elevated = Color(0xFFFFFFFF), Foreground = Color(0xFF11171D),
    Muted = Color(0xFF4C5760), Primary = Color(0xFF006472), OnPrimary = Color(0xFFFCFCFC), Accent = Color(0xFFD2EEF3),
    AccentFg = Color(0xFF003442), Success = Color(0xFF0C6944), Warning = Color(0xFF7E5403), Failure = Color(0xFFAF283D),
    Border = Color(0xFFD6DBE0), Overlay = Color(0xFF05080C), isDark = false,
)

/** Camera, capture review and other photo-first surfaces stay dark whatever the app theme, as on the web. */
val FoDark = DarkPalette

/**
 * The active palette. It is snapshot state, so anything that reads it (in composition or in a draw lambda) updates
 * when the technician switches appearance; [FieldOpsTheme] sets it.
 */
object Fo {
    internal var palette by mutableStateOf(DarkPalette)
    val isDark get() = palette.isDark
    val Background get() = palette.Background
    val Surface get() = palette.Surface
    val Elevated get() = palette.Elevated
    val Foreground get() = palette.Foreground
    val Muted get() = palette.Muted
    val Primary get() = palette.Primary
    val OnPrimary get() = palette.OnPrimary
    val Accent get() = palette.Accent
    val AccentFg get() = palette.AccentFg
    val Success get() = palette.Success
    val Warning get() = palette.Warning
    val Failure get() = palette.Failure
    val Border get() = palette.Border
    val Overlay get() = palette.Overlay
}

/** Provenance colours kept for the existing call sites; mapped onto the design palette. */
object Provenance {
    val Evidence get() = Fo.Success
    val Inferred get() = Fo.Primary
    val Measured get() = Fo.Primary
    val Documented get() = Fo.Muted
    val Simulated get() = Fo.Warning
    val Denied get() = Fo.Failure
}

private fun scheme(p: FoPalette) = (if (p.isDark) darkColorScheme() else lightColorScheme()).copy(
    primary = p.Primary, onPrimary = p.OnPrimary,
    secondary = p.Elevated, onSecondary = p.Foreground,
    background = p.Background, onBackground = p.Foreground,
    surface = p.Background, onSurface = p.Foreground,
    surfaceVariant = p.Surface, onSurfaceVariant = p.Muted,
    surfaceContainer = p.Surface, surfaceContainerHigh = p.Elevated, surfaceContainerLow = p.Surface,
    surfaceContainerHighest = p.Elevated, surfaceContainerLowest = p.Background,
    outline = p.Border, outlineVariant = p.Border,
    error = p.Failure, onError = p.OnPrimary,
)

private val base = Typography()
private fun type(p: FoPalette) = base.copy(
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp, color = p.Muted),
    labelSmall = base.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

/** Small uppercase section label (the design's "eyebrow"). */
val Eyebrow get() = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.3.sp, color = Fo.Muted)
val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)

/** Appearance choice; "System" follows the phone's dark-theme setting. */
enum class Appearance { System, Light, Dark }

@Composable
fun FieldOpsTheme(appearance: Appearance = Appearance.System, content: @Composable () -> Unit) {
    val dark = when (appearance) { Appearance.Light -> false; Appearance.Dark -> true; Appearance.System -> isSystemInDarkTheme() }
    val p = if (dark) DarkPalette else LightPalette
    // Set before any child reads it in this composition, so it is a forward write.
    if (Fo.palette !== p) Fo.palette = p
    MaterialTheme(colorScheme = scheme(p), typography = type(p), content = content)
}

/** Photo-first surfaces (camera, capture review): Material defaults in the dark palette whatever the app theme. */
@Composable
fun ForceDark(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme(DarkPalette), typography = type(DarkPalette)) {
    // Text inherits LocalContentColor from the surrounding (possibly light) screen, so reset it too.
    CompositionLocalProvider(LocalContentColor provides DarkPalette.Foreground, content = content)
}
