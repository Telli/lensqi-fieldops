package com.lensqi.fieldops.ui.task

import com.lensqi.fieldops.domain.live.LiveObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the step screen says about an access step (a ladder or stool to reach [part]); [canSkip] offers to skip it. */
data class ReachAdvice(val text: String, val canSkip: Boolean, val partHeightCm: Int? = null, val reachCm: Int? = null)

/**
 * Whether the person can reach an access step's part standing: its height above the floor, measured by ARCore, against
 * their standing reach, estimated from the height they gave. A 10 cm margin keeps borderline cases on the ladder.
 * Null when the step is not an access step.
 */
fun reachAdvice(accessFor: String?, objects: List<LiveObject>, reachCm: Int?): ReachAdvice? {
    val part = accessFor ?: return null
    val seen = objects.firstOrNull { it.concept.equals(part, ignoreCase = true) && it.heightAboveFloorCm != null }
        ?: return ReachAdvice("To check whether you need this step, start live assist and point at the floor, then at the $part, so the phone can measure its height.", false)
    val h = seen.heightAboveFloorCm!!
    val up = "The $part is about ${metres(h)} up (measured)"
    return when {
        reachCm == null -> ReachAdvice("$up. Add your height in Profile to check whether you can reach it standing.", false, h)
        h <= reachCm - 10 -> ReachAdvice("$up, within your reach standing (about ${metres(reachCm)}, estimated from your height). You may not need this step.", true, h, reachCm)
        else -> ReachAdvice("$up, above your reach standing (about ${metres(reachCm)}, estimated). Keep this step.", false, h, reachCm)
    }
}

/** Parts ARCore measured, as the "measured" field the server accepts beside a frame or a question; null when none. */
fun measuredJson(objects: List<LiveObject>): String? {
    val parts = objects.filter { it.spatialAnchored && it.distanceCm != null && it.concept.trim().split(Regex("\\s+")).size in 1..4 }
        .distinctBy { it.concept.lowercase() }.take(4)
    if (parts.isEmpty()) return null
    return buildJsonArray {
        parts.forEach { o -> add(buildJsonObject { put("part", o.concept.trim()); put("distanceCm", o.distanceCm); o.heightAboveFloorCm?.let { put("heightAboveFloorCm", it) } }) }
    }.toString()
}

private fun metres(cm: Int) = if (cm < 100) "$cm cm" else "${"%.1f".format(cm / 100.0)} m"
