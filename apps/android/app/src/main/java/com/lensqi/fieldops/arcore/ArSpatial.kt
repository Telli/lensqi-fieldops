package com.lensqi.fieldops.arcore

import com.lensqi.fieldops.domain.live.LiveBox
import com.lensqi.fieldops.domain.live.LiveObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure geometry behind the ARCore mode. Everything here is fed by ARCore's own measurements (camera pose, projection,
 * depth image or hit tests); nothing is assumed. "Image" coordinates are the upright analysis image sent to the server,
 * normalized to [0, 1]; "view" coordinates are the on-screen camera view, normalized to [0, 1].
 */

/** Sensor-image point for an upright-image point, when the sensor image is rotated [degrees] clockwise to be upright. */
fun uprightToSensor(u: Double, v: Double, degrees: Int): Pair<Double, Double> = when (degrees) {
    90 -> v to 1 - u
    180 -> 1 - u to 1 - v
    270 -> 1 - v to u
    else -> u to v
}

/** Affine map from upright-image to view coordinates (ARCore crops the camera image to fill the view). */
data class ViewMap(val ox: Double, val oy: Double, val ux: Double, val uy: Double, val vx: Double, val vy: Double) {
    fun point(u: Double, v: Double): Pair<Double, Double> = (ox + u * ux + v * vx) to (oy + u * uy + v * vy)

    /** The box on screen, clipped to the view; null when it falls outside the visible crop. */
    fun box(b: LiveBox): LiveBox? {
        val (x1, y1) = point(b.left, b.top); val (x2, y2) = point(b.right, b.bottom)
        val left = min(x1, x2).coerceIn(0.0, 1.0); val right = max(x1, x2).coerceIn(0.0, 1.0)
        val top = min(y1, y2).coerceIn(0.0, 1.0); val bottom = max(y1, y2).coerceIn(0.0, 1.0)
        return if (right - left < 0.005 || bottom - top < 0.005) null else LiveBox(left, top, right, bottom)
    }

    companion object { val Identity = ViewMap(0.0, 0.0, 1.0, 0.0, 0.0, 1.0) }
}

data class Projected(val x: Double, val y: Double, val forwardMeters: Double)

/**
 * One ARCore frame's camera: the display-oriented camera-to-world matrix and projection matrix (column-major, as
 * ARCore returns them), the upright-image to view map, and the physical camera position for distances.
 */
class ArCameraView(
    private val cameraToWorld: FloatArray,
    private val projection: FloatArray,
    val imageToView: ViewMap,
    private val cameraPosition: DoubleArray,
) {
    /** World point at [forwardMeters] along the camera axis behind a view point. */
    fun unproject(viewX: Double, viewY: Double, forwardMeters: Double): DoubleArray {
        val nx = 2 * viewX - 1; val ny = 1 - 2 * viewY
        val x = (nx + projection[8]) * forwardMeters / projection[0]
        val y = (ny + projection[9]) * forwardMeters / projection[5]
        val z = -forwardMeters
        val m = cameraToWorld
        return doubleArrayOf(m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13], m[2] * x + m[6] * y + m[10] * z + m[14])
    }

    /** Camera-space coordinates of a world point (rigid inverse: Rᵀ(w − t)). */
    fun toCamera(w: DoubleArray): DoubleArray {
        val m = cameraToWorld
        val dx = w[0] - m[12]; val dy = w[1] - m[13]; val dz = w[2] - m[14]
        return doubleArrayOf(m[0] * dx + m[1] * dy + m[2] * dz, m[4] * dx + m[5] * dy + m[6] * dz, m[8] * dx + m[9] * dy + m[10] * dz)
    }

    /** View position and forward depth of a world point; null when it is behind or at the camera. */
    fun project(w: DoubleArray): Projected? {
        val c = toCamera(w); val d = -c[2]
        if (d < 0.05) return null
        val nx = (projection[0] * c[0] + projection[8] * c[2]) / d
        val ny = (projection[5] * c[1] + projection[9] * c[2]) / d
        return Projected((nx + 1) / 2, (1 - ny) / 2, d)
    }

    /** On-screen width and height of a world extent at [forwardMeters], in view units. */
    fun viewExtent(widthMeters: Double, heightMeters: Double, forwardMeters: Double) =
        projection[0] * widthMeters / (2 * forwardMeters) to projection[5] * heightMeters / (2 * forwardMeters)

    fun distanceMeters(w: DoubleArray) = dist(w, cameraPosition)
}

enum class DepthSource(val label: String) { DepthApi("ARCore depth"), HitTest("ARCore hit test") }

/** A measured forward depth at one upright-image point. */
data class DepthSample(val u: Double, val v: Double, val forwardMeters: Double)

/** Depth measured by ARCore for one frame. A box with no measurement inside it gets no depth. */
class DepthSamples(val source: DepthSource, val points: List<DepthSample>) {
    fun forwardIn(b: LiveBox): Double? {
        val inset = 0.2
        val iw = (b.right - b.left) * inset; val ih = (b.bottom - b.top) * inset
        val core = points.filter { it.u in (b.left + iw)..(b.right - iw) && it.v in (b.top + ih)..(b.bottom - ih) }
        val inside = core.ifEmpty { points.filter { it.u in b.left..b.right && it.v in b.top..b.bottom } }
        return inside.map { it.forwardMeters }.filter { it in 0.1..8.0 }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
    }
}

/** An ARCore anchor, or a fake in tests. */
interface WorldAnchor {
    /** World position while ARCore tracks the anchor; null while tracking is paused. */
    fun position(): DoubleArray?
    val stopped: Boolean
    fun detach()
}

/**
 * Parts SAM detected, held in the world by ARCore anchors. An anchor exists only where ARCore measured depth for the
 * detection. It stays while later detections confirm it, and is removed when a detection frame looks right at it and
 * does not find the part, or after [ttlMs] without confirmation. Two parts with the same concept stay two anchors.
 */
class SpatialAnchors(private val ttlMs: Long = 20_000L) {
    private class Entry(
        val id: String, val concept: String, val label: String, val anchor: WorldAnchor,
        val widthMeters: Double, val heightMeters: Double, val confidence: Double?, val source: DepthSource, var confirmedAt: Long,
    )

    private val entries = mutableListOf<Entry>()
    val size get() = entries.size

    fun clear() { entries.forEach { it.anchor.detach() }; entries.clear() }

    /** Anchors one detection frame. Returns how many detections were anchored in the world. */
    fun seed(view: ArCameraView, depth: DepthSamples, detected: List<LiveObject>, timeMs: Long, create: (DoubleArray) -> WorldAnchor?): Int {
        val replaced = mutableSetOf<Entry>()
        val fresh = mutableListOf<Entry>()
        val detectedOnScreen = detected.mapNotNull { o -> view.imageToView.box(o.box)?.let { o to it } }
        for ((o, vb) in detectedOnScreen) {
            val d = depth.forwardIn(o.box) ?: continue
            val cx = (vb.left + vb.right) / 2; val cy = (vb.top + vb.bottom) / 2
            val center = view.unproject(cx, cy, d)
            val width = dist(view.unproject(vb.left, cy, d), view.unproject(vb.right, cy, d))
            val height = dist(view.unproject(cx, vb.top, d), view.unproject(cx, vb.bottom, d))
            // The same physical part seen again: the nearest anchor of this concept within half the part's size.
            val previous = entries.filter { it.concept.equals(o.concept, ignoreCase = true) && it !in replaced }
                .mapNotNull { e -> e.anchor.position()?.let { e to dist(it, center) } }
                .filter { (e, gap) -> gap <= max(0.05, 0.5 * maxOf(width, height, e.widthMeters, e.heightMeters)) }
                .minByOrNull { it.second }?.first
            val anchor = create(center) ?: continue
            previous?.let { replaced += it; it.anchor.detach() }
            fresh += Entry(previous?.id ?: "${o.id}@$timeMs", o.concept, o.label, anchor, width, height, o.confidence, depth.source, timeMs)
        }
        // An anchor this frame looked straight at must be detected again, or it is gone (moved, removed or never there).
        val kept = entries.filter { it !in replaced }.filter { e ->
            val p = e.anchor.position() ?: return@filter true
            val pr = view.project(p) ?: return@filter true
            if (pr.x !in 0.1..0.9 || pr.y !in 0.1..0.9) return@filter true
            val seen = detectedOnScreen.any { (o, vb) -> o.concept.equals(e.concept, ignoreCase = true) && vb.contains(pr.x, pr.y, 0.15) }
            if (seen) e.confirmedAt = timeMs else e.anchor.detach()
            seen
        }
        entries.clear(); entries += kept + fresh
        return fresh.size
    }

    /** Anchors on screen in [view], with their measured distance from the camera. Expired or lost anchors are dropped. */
    fun visible(view: ArCameraView, nowMs: Long): List<LiveObject> {
        expire(nowMs)
        return entries.mapNotNull { e ->
            val p = e.anchor.position() ?: return@mapNotNull null
            val pr = view.project(p) ?: return@mapNotNull null
            val (w, h) = view.viewExtent(e.widthMeters, e.heightMeters, pr.forwardMeters)
            val box = LiveBox((pr.x - w / 2).coerceIn(0.0, 1.0), (pr.y - h / 2).coerceIn(0.0, 1.0), (pr.x + w / 2).coerceIn(0.0, 1.0), (pr.y + h / 2).coerceIn(0.0, 1.0))
            if (box.right - box.left < 0.01 || box.bottom - box.top < 0.01) return@mapNotNull null
            LiveObject(e.id, e.concept, e.label, box, e.confidence, distanceCm = (view.distanceMeters(p) * 100).roundToInt().coerceAtLeast(1),
                spatialAnchored = true, seenSecondsAgo = ((nowMs - e.confirmedAt) / 1000).toInt(), depthSource = e.source.label)
        }
    }

    /** Anchors ARCore still tracks that are outside [view]. */
    fun offscreen(view: ArCameraView, nowMs: Long): Int {
        expire(nowMs)
        return entries.count { e -> e.anchor.position()?.let { p -> view.project(p)?.let { it.x !in 0.0..1.0 || it.y !in 0.0..1.0 } ?: true } ?: false }
    }

    private fun expire(nowMs: Long) {
        entries.removeAll { e -> (e.anchor.stopped || nowMs - e.confirmedAt > ttlMs).also { if (it) e.anchor.detach() } }
    }
}

/**
 * Pairs live 2D tracks with world anchors on screen. A part followed by both keeps the 2D box (it follows the pixels)
 * and gains the anchor's measured distance; an anchor with no live track is shown as last seen; other tracks stay 2D.
 */
fun fuseTracks(tracked: List<LiveObject>, anchored: List<LiveObject>): List<LiveObject> {
    val pool = tracked.toMutableList()
    val fused = anchored.map { a ->
        val match = pool.filter { it.concept.equals(a.concept, ignoreCase = true) }.maxByOrNull { iou(it.box, a.box) }?.takeIf { iou(it.box, a.box) >= 0.2 }
        if (match == null) a else { pool.remove(match); match.copy(distanceCm = a.distanceCm, spatialAnchored = true, seenSecondsAgo = null, depthSource = a.depthSource) }
    }
    return fused + pool
}

/** Distance coaching from a measured anchor distance; null when no part has one. */
fun distanceAdvice(objects: List<LiveObject>, targetConcept: String? = null): String? {
    val measured = objects.filter { it.spatialAnchored && it.distanceCm != null }
    val part = targetConcept?.let { t -> measured.firstOrNull { it.concept.equals(t, ignoreCase = true) } } ?: measured.minByOrNull { it.distanceCm!! } ?: return null
    val cm = part.distanceCm!!; val name = part.label.lowercase()
    return when {
        cm > 45 -> "Move about ${cm - 30} cm closer to the $name (about $cm cm away)."
        cm < 16 -> "Move back about ${22 - cm} cm from the $name so the lens can focus."
        else -> "Good distance from the $name (about $cm cm). Capture when ready."
    }
}

fun iou(a: LiveBox, b: LiveBox): Double {
    val w = min(a.right, b.right) - max(a.left, b.left); val h = min(a.bottom, b.bottom) - max(a.top, b.top)
    if (w <= 0 || h <= 0) return 0.0
    val inter = w * h
    return inter / ((a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - inter)
}

private fun LiveBox.contains(x: Double, y: Double, margin: Double): Boolean {
    val mw = (right - left) * margin; val mh = (bottom - top) * margin
    return x in (left - mw)..(right + mw) && y in (top - mh)..(bottom + mh)
}

private fun dist(a: DoubleArray, b: DoubleArray) = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
