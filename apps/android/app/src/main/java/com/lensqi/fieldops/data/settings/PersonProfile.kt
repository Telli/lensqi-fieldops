package com.lensqi.fieldops.data.settings

import kotlin.math.roundToInt

/** What the person told LensQi about themselves, sent with each new task so the plan and coaching fit them. */
data class PersonProfile(val heightCm: Int? = null, val tools: Set<String> = emptySet()) {
    /** Overhead reach standing on the floor, estimated as 1.33 × height (the server uses the same estimate). */
    val standingReachCm: Int? get() = heightCm?.let { (it * 1.33).roundToInt() }

    companion object {
        val ToolChoices = listOf("step stool", "ladder")
        const val MinHeightCm = 90
        const val MaxHeightCm = 250
    }
}

/**
 * A height as people say it: 6'2", 6 ft 2, 6ft2in, 74 in, 188, 188 cm or 1.88 m. Null when it can't be read or falls
 * outside 90 to 250 cm.
 */
fun parseHeightCm(text: String): Int? {
    val t = text.trim().lowercase().replace('’', '\'').replace('”', '"').replace("″", "\"").replace("′", "'")
    if (t.isEmpty()) return null
    val feet = Regex("""^(\d)\s*(?:'|ft|feet|foot)\s*(?:(\d{1,2}(?:\.\d+)?)\s*(?:"|''|in|inches|inch)?)?$""").find(t)
    val cm = when {
        feet != null -> (feet.groupValues[1].toInt() * 12 + (feet.groupValues[2].toDoubleOrNull() ?: 0.0)) * 2.54
        Regex("""^(\d{2,3}(?:\.\d+)?)\s*(?:"|in|inches|inch)$""").find(t) != null -> t.takeWhile { it.isDigit() || it == '.' }.toDouble() * 2.54
        Regex("""^(\d(?:\.\d+)?)\s*m$""").find(t) != null -> t.takeWhile { it.isDigit() || it == '.' }.toDouble() * 100
        Regex("""^(\d{2,3})\s*(?:cm)?$""").find(t) != null -> t.takeWhile { it.isDigit() }.toDouble()
        Regex("""^1\.\d+$""").find(t) != null -> t.toDouble() * 100
        else -> return null
    }
    return cm.roundToInt().takeIf { it in PersonProfile.MinHeightCm..PersonProfile.MaxHeightCm }
}
