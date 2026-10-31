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

    /**
     * Which way to turn toward a world point that is off screen, or null while it is on screen. The direction comes
     * from the camera-space vector rather than the projected point, which mirrors for a point behind the camera.
     */
    fun edgeCue(w: DoubleArray): EdgeCue? {
        val c = toCamera(w)
        project(w)?.let { if (it.x in 0.0..1.0 && it.y in 0.0..1.0) return null }
        return EdgeCue(projection[0] * c[0], -projection[5] * c[1], behind = -c[2] < 0.05)
    }

    fun distanceMeters(w: DoubleArray) = dist(w, cameraPosition)

    /** The camera's height in world metres (Y up). */
    val cameraY: Double get() = cameraPosition[1]
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

/** Which way to turn toward a point off screen, in view units (x right, y down); [behind] when it is behind the camera. */
data class EdgeCue(val dx: Double, val dy: Double, val behind: Boolean)

/** How far the screen can trust the confirmed part to be where its anchor is. */
enum class TargetState { Tracking, Uncertain, Lost }

/** The part the technician confirmed, as the screen shows it. */
data class TargetView(
    val id: String, val concept: String, val label: String, val state: TargetState,
    /** Seconds since a detection last matched it, or since the technician confirmed it. */
    val seenSecondsAgo: Int,
    /** Two parts with its name both fit where it is, so the technician must say which one it is. */
    val ambiguous: Boolean = false,
    /** Set while it is off screen or behind the camera. */
    val cue: EdgeCue? = null,
)

/**
 * Parts SAM detected, held in the world by ARCore anchors. An anchor exists only where ARCore measured depth for the
 * detection. It stays while later detections confirm it, and is removed when a detection frame looks right at it and
 * does not find the part, or after [ttlMs] without confirmation.
 *
 * One physical part keeps one anchor. A new detection is the same part when the anchor projects inside its box on
 * screen, or lies within a tolerance that grows with distance (ARCore's depth on a plain ceiling 3 m away is off by tens
 * of centimetres); under another name it must also overlap the box. Each sighting averages the position into an offset
 * from the anchor, so noise settles the label rather than splitting it, and the anchor is replaced only when the part
 * moves by more than its own size. [preferredConcept] (a task step's own part) names a part seen under two names. Two
 * separate parts with the same name stay two anchors.
 *
 * The technician can [confirm] one part as the target. It follows stricter rules: a detection updates it only when it has
 * the same name, its anchor lies inside the box and no other box of that name also holds it; two such boxes ask the
 * technician instead. A miss or a hand over it makes it uncertain rather than removing it; after [lostMs] without a match,
 * or when ARCore stops tracking its anchor, it is lost and must be confirmed again.
 */
class SpatialAnchors(
    private val ttlMs: Long = 20_000L,
    /** A confirmed part not matched for this long is uncertain: faded, with no distance coaching. */
    private val uncertainMs: Long = 12_000L,
    private val lostMs: Long = 60_000L,
) {
    private class Entry(
        val id: String, var concept: String, var label: String, var anchor: WorldAnchor, var offset: DoubleArray,
        var widthMeters: Double, var heightMeters: Double, var confidence: Double?, var source: DepthSource, var confirmedAt: Long,
        var sightings: Int,
    ) {
        var target = false
        /** When a detection frame looked straight at the confirmed part and did not find it; cleared by the next match. */
        var missedAt: Long? = null
        fun position(): DoubleArray? = anchor.position()?.let { p -> DoubleArray(3) { p[it] + offset[it] } }
    }

    private class Sighting(val o: LiveObject, val vb: LiveBox, val center: DoubleArray, val width: Double, val height: Double, val forward: Double)
    private class Lost(val id: String, val concept: String, val label: String, val seenAt: Long)

    private val entries = mutableListOf<Entry>()
    private var ambiguous = false
    private var lost: Lost? = null
    val size get() = entries.size
    /** Anchors created and detached so far, for the session log. */
    var created = 0; private set
    var detached = 0; private set

    /** The step's own part: when one part is seen under two names, it takes this one. */
    @Volatile var preferredConcept: String? = null

    fun clear() { entries.forEach { it.anchor.detach() }; detached += entries.size; entries.clear(); lost = null; ambiguous = false }

    private fun preferred(concept: String) = preferredConcept?.equals(concept, ignoreCase = true) == true

    /** Makes the part anchored as [id] the target; any earlier target goes back to an ordinary label. */
    fun confirm(id: String, nowMs: Long): Boolean {
        val e = entries.firstOrNull { it.id == id } ?: return false
        entries.forEach { it.target = false }
        e.target = true; e.missedAt = null; e.confirmedAt = nowMs
        lost = null; ambiguous = false
        return true
    }

    /** Anchors one detection frame. Returns how many detections were anchored in the world. */
    fun seed(view: ArCameraView, depth: DepthSamples, detected: List<LiveObject>, timeMs: Long, create: (DoubleArray) -> WorldAnchor?): Int {
        val detectedOnScreen = onePerPart(detected.mapNotNull { o -> view.imageToView.box(o.box)?.let { o to it } }, ::preferred)
        var sightings = detectedOnScreen.mapNotNull { (o, vb) -> measure(view, depth, o, vb) }
        val updated = mutableSetOf<Entry>()
        entries.firstOrNull { it.target }?.let { t -> sightings = seedTarget(t, sightings, view, timeMs, create, updated) }
        for (s in sightings) {
            val previous = entries.filter { !it.target && it !in updated }
                .mapNotNull { e -> e.position()?.let { e to it } }
                .filter { (e, p) -> samePart(e, p, s.o.concept, s.vb, s.center, view, maxOf(s.width, s.height), s.forward) }
                .minByOrNull { (_, p) -> dist(p, s.center) }
            if (previous != null) {
                val (e, p) = previous
                // The step's own name wins; otherwise the part keeps the name it was first anchored under.
                if (preferred(s.o.concept) && !preferred(e.concept)) { e.concept = s.o.concept; e.label = s.o.label }
                if (update(e, s, p, timeMs, create)) updated += e
            } else {
                val anchor = create(s.center) ?: continue
                created++
                updated += Entry("${s.o.id}@$timeMs", s.o.concept, s.o.label, anchor, DoubleArray(3), s.width, s.height, s.o.confidence, depth.source, timeMs, 1)
                    .also { entries += it }
            }
        }
        // An anchor this frame looked straight at must be detected again (under any name), or it is gone. The target is
        // only marked missed: a hand in front of it is not the part going away.
        entries.removeAll { e ->
            if (e in updated) return@removeAll false
            val p = e.position() ?: return@removeAll false
            val pr = view.project(p) ?: return@removeAll false
            if (pr.x !in 0.1..0.9 || pr.y !in 0.1..0.9) return@removeAll false
            val seen = detectedOnScreen.any { (_, vb) -> vb.contains(pr.x, pr.y, 0.15) }
            when {
                seen -> { if (!e.target) e.confirmedAt = timeMs; false }
                e.target -> { if (e.missedAt == null) e.missedAt = timeMs; false }
                else -> { e.anchor.detach(); detached++; true }
            }
        }
        return updated.size
    }

    /**
     * Matches the confirmed part strictly and returns the sightings left for ordinary labels. Boxes of its name around it
     * that it took no part in (the same part split by noisy depth) are dropped rather than becoming a second label.
     */
    private fun seedTarget(t: Entry, sightings: List<Sighting>, view: ArCameraView, timeMs: Long, create: (DoubleArray) -> WorldAnchor?, updated: MutableSet<Entry>): List<Sighting> {
        val p = t.position() ?: return sightings
        val pr = view.project(p)
        val same = sightings.filter { it.o.concept.equals(t.concept, ignoreCase = true) }
        val holding = same.filter { pr != null && it.vb.contains(pr.x, pr.y, 0.05) }
        ambiguous = holding.size > 1
        if (holding.size != 1) return sightings
        val match = holding.single()
        if (update(t, match, p, timeMs, create)) { updated += t; t.missedAt = null }
        val near = max(0.05, max(t.widthMeters, t.heightMeters))
        return sightings.filter { it !== match && !(it in same && dist(it.center, p) <= near) }
    }

    private fun measure(view: ArCameraView, depth: DepthSamples, o: LiveObject, vb: LiveBox): Sighting? {
        val d = depth.forwardIn(o.box) ?: return null
        val cx = (vb.left + vb.right) / 2; val cy = (vb.top + vb.bottom) / 2
        val width = dist(view.unproject(vb.left, cy, d), view.unproject(vb.right, cy, d))
        val height = dist(view.unproject(cx, vb.top, d), view.unproject(cx, vb.bottom, d))
        return Sighting(o, vb, view.unproject(cx, cy, d), width, height, d)
    }

    /** Averages a sighting into [e] (weighted by up to four earlier sightings), keeping its anchor unless the part moved. */
    private fun update(e: Entry, s: Sighting, p: DoubleArray, timeMs: Long, create: (DoubleArray) -> WorldAnchor?): Boolean {
        val w = min(e.sightings, 4).toDouble()
        val at = DoubleArray(3) { i -> (p[i] * w + s.center[i]) / (w + 1) }
        val base = e.anchor.position()
        if (base == null || dist(at, base) > max(0.05, max(e.widthMeters, e.heightMeters))) {
            val anchor = create(at) ?: return false
            e.anchor.detach(); detached++; created++
            e.anchor = anchor; e.offset = DoubleArray(3)
        } else e.offset = DoubleArray(3) { at[it] - base[it] }
        e.widthMeters = (e.widthMeters + s.width) / 2; e.heightMeters = (e.heightMeters + s.height) / 2
        e.confidence = s.o.confidence ?: e.confidence
        e.confirmedAt = timeMs; e.sightings++
        return true
    }

    private fun samePart(e: Entry, p: DoubleArray, concept: String, vb: LiveBox, center: DoubleArray, view: ArCameraView, sizeMeters: Double, forward: Double): Boolean {
        val pr = view.project(p)
        val inside = pr != null && vb.contains(pr.x, pr.y, 0.05)
        if (!e.concept.equals(concept, ignoreCase = true)) {
            // Another name for the same part: it must sit inside the new box and overlap it like one object.
            if (!inside) return false
            val (w, h) = view.viewExtent(e.widthMeters, e.heightMeters, pr!!.forwardMeters)
            return iou(LiveBox(pr.x - w / 2, pr.y - h / 2, pr.x + w / 2, pr.y + h / 2), vb) >= 0.4
        }
        val tolerance = max(0.05, max(0.5 * maxOf(sizeMeters, e.widthMeters, e.heightMeters), 0.15 * forward))
        return inside || dist(p, center) <= tolerance
    }

    private fun Entry.uncertain(nowMs: Long) = missedAt != null || nowMs - confirmedAt > uncertainMs

    /**
     * Anchors on screen in [view], with their measured distance from the camera, their height relative to the camera
     * and, when the technician has set the floor ([floorY], world metres, Y up), their height above it. Expired or lost
     * anchors are dropped.
     */
    fun visible(view: ArCameraView, nowMs: Long, floorY: Double? = null): List<LiveObject> {
        expire(nowMs)
        return entries.mapNotNull { e ->
            val p = e.position() ?: return@mapNotNull null
            val pr = view.project(p) ?: return@mapNotNull null
            val (w, h) = view.viewExtent(e.widthMeters, e.heightMeters, pr.forwardMeters)
            val box = LiveBox((pr.x - w / 2).coerceIn(0.0, 1.0), (pr.y - h / 2).coerceIn(0.0, 1.0), (pr.x + w / 2).coerceIn(0.0, 1.0), (pr.y + h / 2).coerceIn(0.0, 1.0))
            if (box.right - box.left < 0.01 || box.bottom - box.top < 0.01) return@mapNotNull null
            LiveObject(e.id, e.concept, e.label, box, e.confidence, distanceCm = (view.distanceMeters(p) * 100).roundToInt().coerceAtLeast(1),
                spatialAnchored = true, seenSecondsAgo = ((nowMs - e.confirmedAt) / 1000).toInt(), depthSource = e.source.label,
                heightAboveFloorCm = floorY?.let { ((p[1] - it) * 100).roundToInt() },
                anchorId = e.id, target = e.target, uncertain = e.target && e.uncertain(nowMs),
                aboveCameraCm = ((p[1] - view.cameraY) * 100).roundToInt())
        }
    }

    /** Anchors ARCore still tracks that are outside [view]. */
    fun offscreen(view: ArCameraView, nowMs: Long): Int {
        expire(nowMs)
        return entries.count { e -> e.position()?.let { p -> view.project(p)?.let { it.x !in 0.0..1.0 || it.y !in 0.0..1.0 } ?: true } ?: false }
    }

    /** The confirmed part, or null when there is none. [view] is null while ARCore is not tracking the camera. */
    fun target(view: ArCameraView?, nowMs: Long): TargetView? {
        expire(nowMs)
        val e = entries.firstOrNull { it.target }
            ?: return lost?.let { TargetView(it.id, it.concept, it.label, TargetState.Lost, ((nowMs - it.seenAt) / 1000).toInt()) }
        val p = e.position()
        val state = if (p == null || view == null || e.uncertain(nowMs)) TargetState.Uncertain else TargetState.Tracking
        return TargetView(e.id, e.concept, e.label, state, ((nowMs - e.confirmedAt) / 1000).toInt(), ambiguous,
            if (p != null && view != null) view.edgeCue(p) else null)
    }

    private fun expire(nowMs: Long) {
        entries.removeAll { e ->
            val gone = e.anchor.stopped || nowMs - e.confirmedAt > (if (e.target) lostMs else ttlMs)
            if (gone) {
                if (e.target) { lost = Lost(e.id, e.concept, e.label, e.confirmedAt); ambiguous = false }
                e.anchor.detach(); detached++
            }
            gone
        }
    }
}

/**
 * The floor's height (world metres, Y up), set only where the technician tapped a floor ARCore found. It is forgotten
 * when ARCore loses tracking for more than [graceMs], since the world can shift when tracking comes back.
 */
class FloorReference(private val graceMs: Long = 3_000L) {
    var y: Double? = null; private set
    private var lostAt: Long? = null

    fun set(y: Double) { this.y = y; lostAt = null }
    fun clear() { y = null }

    fun tracking(ok: Boolean, nowMs: Long) {
        if (ok) { lostAt = null; return }
        val since = lostAt ?: nowMs.also { lostAt = it }
        if (nowMs - since > graceMs) y = null
    }
}

/** What the screen says about the floor: whether it is set, how high the phone is above it, and the last tap's result. */
data class FloorStatus(val set: Boolean, val cameraHeightCm: Int?, val note: String?)

/**
 * One detection per physical part in a frame: a box that overlaps a kept one by half (IoU), or lies mostly inside a kept
 * box of the same name, is that part found again. The preferred name (the step's part) and then confidence decide which
 * is kept, so a detector found as "smoke detector" and "ceiling detector" is one smoke detector, while a test button
 * inside it stays its own part.
 */
fun onePerPart(found: List<Pair<LiveObject, LiveBox>>, preferred: (String) -> Boolean = { false }): List<Pair<LiveObject, LiveBox>> {
    val kept = mutableListOf<Pair<LiveObject, LiveBox>>()
    for (f in found.sortedWith(compareByDescending<Pair<LiveObject, LiveBox>> { preferred(it.first.concept) }.thenByDescending { it.first.confidence ?: 0.0 })) {
        val (o, vb) = f
        val same = kept.any { (k, kb) ->
            iou(kb, vb) >= 0.5 || k.concept.equals(o.concept, ignoreCase = true) && overlapOfSmaller(kb, vb) >= 0.8
        }
        if (!same) kept += f
    }
    return kept
}

/**
 * The parts worth drawing: the confirmed part first, then the step's part, then the nearest measured, then the most
 * confident, at most [max];
 * a box that mostly overlaps one already chosen (a 2D track beside its anchor) is the same part. Returns the parts and
 * how many more were left out, for a "+N more" note instead of a screen full of labels.
 */
fun partsToShow(objects: List<LiveObject>, target: String?, max: Int = 2): Pair<List<LiveObject>, Int> {
    val ordered = objects.sortedWith(
        compareByDescending<LiveObject> { it.target }.thenByDescending { target != null && it.concept.equals(target, ignoreCase = true) }
            .thenBy { it.distanceCm ?: Int.MAX_VALUE }.thenByDescending { it.confidence ?: 0.0 })
    val distinct = mutableListOf<LiveObject>()
    for (o in ordered) if (distinct.none { iou(it.box, o.box) >= 0.5 }) distinct += o
    return distinct.take(max) to (distinct.size - max).coerceAtLeast(0)
}

private fun overlapOfSmaller(a: LiveBox, b: LiveBox): Double {
    val w = min(a.right, b.right) - max(a.left, b.left); val h = min(a.bottom, b.bottom) - max(a.top, b.top)
    if (w <= 0 || h <= 0) return 0.0
    val smaller = minOf((a.right - a.left) * (a.bottom - a.top), (b.right - b.left) * (b.bottom - b.top))
    return if (smaller <= 0) 0.0 else w * h / smaller
}

/**
 * Pairs live 2D tracks with world anchors on screen. A part followed by both keeps the 2D box (it follows the pixels)
 * and gains the anchor's measured distance; an anchor with no live track is shown as last seen; other tracks stay 2D.
 */
fun fuseTracks(tracked: List<LiveObject>, anchored: List<LiveObject>): List<LiveObject> {
    val pool = tracked.toMutableList()
    val fused = anchored.map { a ->
        val match = pool.filter { it.concept.equals(a.concept, ignoreCase = true) }.maxByOrNull { iou(it.box, a.box) }?.takeIf { iou(it.box, a.box) >= 0.2 }
        if (match == null) a else {
            pool.remove(match)
            match.copy(distanceCm = a.distanceCm, spatialAnchored = true, seenSecondsAgo = null, depthSource = a.depthSource,
                heightAboveFloorCm = a.heightAboveFloorCm, anchorId = a.anchorId, target = a.target, uncertain = a.uncertain, aboveCameraCm = a.aboveCameraCm)
        }
    }
    return fused + pool
}

/**
 * Distance coaching from the measured distance to one part: the confirmed part when there is one, otherwise the step's
 * part, otherwise the nearest measured one. Stepping closer or back is coached only against a [framingCm] range the task
 * or view request gives; without one only the lens's near limit is. Nothing is coached for an uncertain part, a part
 * well above the camera (reaching it is a ladder question, not a framing one) or one further than 1.5 m, where depth is
 * too rough to count centimetres. Distances are estimates: the median depth inside the part's box.
 */
fun distanceAdvice(objects: List<LiveObject>, targetConcept: String? = null, framingCm: IntRange? = null): String? {
    val measured = objects.filter { it.spatialAnchored && it.distanceCm != null && !it.uncertain }
    val confirmed = objects.firstOrNull { it.target }
    val part = when {
        confirmed != null -> confirmed.takeIf { it in measured }
        targetConcept != null -> measured.firstOrNull { it.concept.equals(targetConcept, ignoreCase = true) }
        else -> measured.minByOrNull { it.distanceCm!! }
    } ?: return null
    val cm = part.distanceCm!!; val name = part.label.lowercase()
    if ((part.aboveCameraCm ?: 0) > 50 || cm > 150) return null
    return when {
        cm < 16 -> "Move back about ${22 - cm} cm from the $name so the lens can focus (estimated $cm cm away)."
        framingCm == null -> null
        cm > framingCm.last -> "Move about ${cm - (framingCm.first + framingCm.last) / 2} cm closer to the $name (estimated $cm cm away)."
        cm < framingCm.first -> "Move back about ${(framingCm.first + framingCm.last) / 2 - cm} cm from the $name (estimated $cm cm away)."
        else -> "Good distance from the $name (estimated $cm cm). Capture when ready."
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

internal fun dist(a: DoubleArray, b: DoubleArray) = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
