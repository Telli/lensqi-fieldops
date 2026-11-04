package com.lensqi.fieldops.ui.camera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lensqi.fieldops.arcore.TargetState
import com.lensqi.fieldops.arcore.TargetView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * The part the technician confirmed, kept outside the AR session: the shutter, a rotation or a pause ends the session
 * and its anchors, and the screen then asks the technician to find this part again and confirm it. Only the identity
 * is kept, never a position, because a new session has new world coordinates. A view model owns one so it outlives the
 * camera screen; [scope] is the step, task or inspection it belongs to.
 */
class TargetMemory {
    var label: String? by mutableStateOf(null); private set
    var concept: String? by mutableStateOf(null); private set
    var scope: Any? = null

    fun hold(concept: String, label: String) { this.concept = concept; this.label = label }
    fun forget() { concept = null; label = null }
}

internal const val TARGET_TIP = "Tap a part to hold it as the target"

/** What the screen tells the technician about the target, or null when there is nothing to say. */
fun targetNote(target: TargetView?, remembered: String?, anchoredParts: Boolean): String? {
    val label = target?.label?.lowercase()
    return when {
        target?.ambiguous == true -> "Two ${label}s fit here · tap the one you're working on"
        target?.state == TargetState.Lost -> "Lost the $label · find it and tap it to confirm again"
        target?.state == TargetState.Uncertain ->
            "Not seeing the $label${if (target.seenSecondsAgo > 0) " · last matched ${target.seenSecondsAgo}s ago" else ""} · keep it in view"
        target != null -> null
        remembered != null -> "Find the ${remembered.lowercase()} again and tap it to confirm"
        anchoredParts -> TARGET_TIP
        else -> null
    }
}

/**
 * Where an off-screen arrow sits: the point where a ray from the centre of a [width] × [height] view, in direction
 * ([dx], [dy]) in view units, meets the view inset by [inset] pixels. Null when the direction is too small to point.
 */
fun edgePoint(dx: Double, dy: Double, width: Float, height: Float, inset: Float): Pair<Float, Float>? {
    val x = dx * width; val y = dy * height
    val length = hypot(x, y)
    if (length < 1e-6) return null
    val ux = x / length; val uy = y / length
    val hw = width / 2 - inset; val hh = height / 2 - inset
    val t = min(if (abs(ux) < 1e-9) Double.MAX_VALUE else hw / abs(ux), if (abs(uy) < 1e-9) Double.MAX_VALUE else hh / abs(uy))
    return (width / 2 + ux * t).toFloat() to (height / 2 + uy * t).toFloat()
}
