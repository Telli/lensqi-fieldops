package com.lensqi.fieldops.ui.camera

import com.lensqi.fieldops.domain.live.LiveBox
import com.lensqi.fieldops.domain.live.LiveObject
import kotlin.math.abs
import kotlin.math.max

/** What a drawn part's label says: its name, then what kind of label it is and what was measured. */
internal data class PartLabel(val title: String, val detail: String)

/**
 * A part the AI found is labelled as a suggestion; only the part the person confirmed is theirs. Distances are estimates
 * (the median depth in the box) and are left off while the target is uncertain.
 */
internal fun partLabel(part: LiveObject): PartLabel = PartLabel(
    part.label.replaceFirstChar { it.uppercase() },
    listOfNotNull(
        if (part.target) "Your target" else "AI suggestion",
        part.distanceCm?.takeUnless { part.uncertain }?.let { "est. ${metres(it)} away" },
        part.heightAboveFloorCm?.let { "${metres(it)} up" },
        part.seenSecondsAgo?.let { "last seen $it s ago" },
    ).joinToString(" · "),
)

internal data class LabelRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun overlaps(other: LabelRect): Boolean = left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/** The part of the [image] a label may use: clear of whatever covers the picture above [clearTop] and below [clearBottom]. */
internal fun labelBounds(image: LabelRect, clearTop: Float, clearBottom: Float, edge: Float): LabelRect =
    LabelRect(image.left + edge, maxOf(image.top, clearTop) + edge, image.right - edge, minOf(image.bottom, clearBottom) - edge)

/**
 * Where a [width] by [height] label goes for a part drawn at [part]: above it, else below it, else just inside its top
 * edge, always wholly inside [bounds] and clear of the labels in [taken] where there is room. [bounds] excludes whatever
 * covers the picture, so a label is never drawn under the header or the controls.
 */
internal fun placeLabel(part: LabelRect, width: Float, height: Float, bounds: LabelRect, gap: Float, taken: List<LabelRect> = emptyList()): LabelRect {
    val left = part.left.coerceIn(bounds.left, max(bounds.left, bounds.right - width))
    fun at(top: Float) = LabelRect(left, top, left + width, top + height)
    fun clear(rect: LabelRect) = taken.none { it.overlaps(rect) }
    fun fits(top: Float) = top >= bounds.top && top + height <= bounds.bottom
    val wanted = listOf(part.top - gap - height, part.bottom + gap, part.top + gap)
    wanted.firstOrNull { fits(it) && clear(at(it)) }?.let { return at(it) }
    val clamped = wanted.map { it.coerceIn(bounds.top, max(bounds.top, bounds.bottom - height)) }
    return at(clamped.firstOrNull { clear(at(it)) } ?: clamped.first())
}

/**
 * Steadies the drawn outlines. A part held still shouldn't shiver with detection noise, so a change too small to mean
 * anything is ignored and a real move is followed over a few frames; a big jump is a new position and is taken at once.
 * For drawing only: taps still use the boxes as tracked.
 */
internal class BoxSmoother(private val deadZone: Double = 0.004, private val follow: Double = 0.5, private val snap: Double = 0.15) {
    private val held = mutableMapOf<String, LiveBox>()

    fun smooth(parts: List<LiveObject>): List<LiveObject> {
        val seen = mutableSetOf<String>()
        val drawn = parts.map { part ->
            val key = part.anchorId ?: "${part.concept}/${part.id}"
            seen += key
            val before = held[key]
            val move = before?.let {
                maxOf(abs(part.box.left - it.left), abs(part.box.top - it.top), abs(part.box.right - it.right), abs(part.box.bottom - it.bottom))
            }
            val box = when {
                before == null || move == null || move > snap -> part.box
                move <= deadZone -> before
                else -> LiveBox(blend(before.left, part.box.left), blend(before.top, part.box.top), blend(before.right, part.box.right), blend(before.bottom, part.box.bottom))
            }
            held[key] = box
            part.copy(box = box)
        }
        held.keys.retainAll(seen)
        return drawn
    }

    private fun blend(from: Double, to: Double) = from + (to - from) * follow
}
