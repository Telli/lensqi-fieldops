package com.lensqi.fieldops.domain.live

import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable data class LiveBox(val left: Double, val top: Double, val right: Double, val bottom: Double)
@Serializable data class LiveObject(
    val id: String,
    val concept: String,
    val label: String,
    val box: LiveBox,
    val confidence: Double? = null,
    /** Camera-to-part distance measured by ARCore; never estimated. */
    val distanceCm: Int? = null,
    val spatialAnchored: Boolean = false,
    /** Set only when the box comes from a world anchor with no live 2D track: seconds since a detection confirmed it. */
    val seenSecondsAgo: Int? = null,
    val depthSource: String? = null,
    /** Height above the floor the technician set, from the part's anchor; null until the floor is set. Measured. */
    val heightAboveFloorCm: Int? = null,
    /** The world anchor holding this part, which the technician can confirm as the target. */
    val anchorId: String? = null,
    /** The part the technician confirmed. */
    val target: Boolean = false,
    /** The confirmed part has not been matched lately or was missed when looked at; no distance is coached on it. */
    val uncertain: Boolean = false,
    /** Height of the part's anchor above (positive) or below the camera, from ARCore's pose; needs no floor. */
    val aboveCameraCm: Int? = null,
)
@Serializable data class LiveFinding(val featureId: String, val status: String, val reason: String)
@Serializable data class LiveResponse(val frameId: String, val objects: List<LiveObject> = emptyList(), val recommendation: String? = null,
    val findings: List<LiveFinding> = emptyList(), val refreshAfterMs: Long = 4000, val inferenceMs: Long = 0,
    /** Error or status text the AI read off a screen in view, word for word. */
    val screenText: String? = null)
data class GrayFrame(val id: String, val time: Long, val width: Int, val height: Int, val pixels: IntArray)
private fun median(values: List<Double>) = values.sorted().getOrElse(values.size / 2) { 0.0 }
fun difference(a: GrayFrame, b: GrayFrame): Double {
    if (a.width != b.width || a.height != b.height) return 255.0
    var sum = 0L; var n = 0
    for (i in a.pixels.indices step 3) { val d = a.pixels[i] - b.pixels[i]; sum += d * d; n++ }
    return sqrt(sum.toDouble() / n)
}
data class FrameQuality(val brightness: Double, val sharpness: Double)
fun frameQuality(f: GrayFrame): FrameQuality {
    var light = 0L; var edge = 0L
    for (y in 1 until f.height - 1) for (x in 1 until f.width - 1) {
        val p = y * f.width + x; val v = f.pixels[p]; light += v
        edge += abs(4 * v - f.pixels[p - 1] - f.pixels[p + 1] - f.pixels[p - f.width] - f.pixels[p + f.width])
    }
    val n = (f.width - 2) * (f.height - 2)
    return FrameQuality(light.toDouble() / n, edge.toDouble() / n)
}
private data class Point(val x: Int, val y: Int, val dx: Int, val dy: Int)
fun trackBox(a: GrayFrame, b: GrayFrame, box: LiveBox): LiveBox? {
    if (a.width != b.width || a.height != b.height || b.time - a.time > 700) return null
    val w = a.width; val h = a.height
    val points = mutableListOf<Point>(); var textured = 0
    for (gy in 0..4) for (gx in 0..4) {
        val x = ((box.left + (gx + .5) / 5 * (box.right - box.left)) * w).roundToInt()
        val y = ((box.top + (gy + .5) / 5 * (box.bottom - box.top)) * h).roundToInt()
        if (x < 9 || y < 9 || x >= w - 9 || y >= h - 9) continue
        var sum = 0.0; var sq = 0.0
        for (py in -2..2) for (px in -2..2) { val v = a.pixels[(y + py) * w + x + px]; sum += v; sq += v * v }
        if (sq / 25 - (sum / 25).pow(2) < 80) continue
        textured++
        var best = Double.POSITIVE_INFINITY; var dx = 0; var dy = 0
        for (sy in -6..6) for (sx in -6..6) {
            var score = 0.0
            for (py in -2..2) for (px in -2..2) score += abs(a.pixels[(y + py) * w + x + px] - b.pixels[(y + sy + py) * w + x + sx + px])
            score = score / 25 + .015 * (sx * sx + sy * sy)
            if (score < best) { best = score; dx = sx; dy = sy }
        }
        if (best < 22) points.add(Point(x, y, dx, dy))
    }
    if (points.size < 4 || points.size < textured * .5) return null
    val dx = median(points.map { it.dx.toDouble() }); val dy = median(points.map { it.dy.toDouble() })
    val consistent = points.filter { abs(it.dx - dx) <= 2 && abs(it.dy - dy) <= 2 }
    if (consistent.size < 4 || consistent.size < points.size * .6) return null
    val scales = mutableListOf<Double>()
    for (i in consistent.indices) for (j in i + 1 until consistent.size) {
        val p = consistent[i]; val q = consistent[j]; val before = hypot((p.x - q.x).toDouble(), (p.y - q.y).toDouble())
        if (before >= 12) scales.add(hypot((p.x + p.dx - q.x - q.dx).toDouble(), (p.y + p.dy - q.y - q.dy).toDouble()) / before)
    }
    val scale = if (scales.isEmpty()) 1.0 else median(scales)
    if (scale < .85 || scale > 1.15) return null
    val cx = (box.left + box.right) / 2 + dx / w; val cy = (box.top + box.bottom) / 2 + dy / h
    val halfW = (box.right - box.left) * scale / 2; val halfH = (box.bottom - box.top) * scale / 2
    if (cx - halfW < 0 || cy - halfH < 0 || cx + halfW > 1 || cy + halfH > 1) return null
    return LiveBox(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
}

/** Bounded history replays late detections onto the current frame, instead of painting old coordinates onto live video. */
class LiveTracker {
    val frames = ArrayDeque<GrayFrame>()
    var objects: List<LiveObject> = emptyList(); private set
    private var detectedAt = 0L
    fun push(frame: GrayFrame) {
        val prev = frames.lastOrNull()
        if (prev != null && (prev.width != frame.width || prev.height != frame.height || frame.time - prev.time > 700)) { frames.clear(); objects = emptyList() }
        if (prev != null && objects.isNotEmpty()) objects = advance(prev, frame, objects)
        frames.addLast(frame)
        while (frames.size > 240 || frame.time - frames.first().time > 24000) frames.removeFirst()
        if (frame.time - detectedAt > 20000) objects = emptyList()
    }
    fun seed(frameId: String, detected: List<LiveObject>): Boolean {
        val index = frames.indexOfFirst { it.id == frameId }; if (index < 0) return false
        detectedAt = frames[index].time
        var tracked = detected.filter { o -> o.box.run { listOf(left, top, right, bottom).all { it.isFinite() } && left >= 0 && top >= 0 && right <= 1 && bottom <= 1 && right > left && bottom > top } }
        for (n in index + 1 until frames.size) tracked = advance(frames[n - 1], frames[n], tracked)
        objects = tracked; return true
    }
    /** Forgets every frame and track, so a response to an earlier frame finds nothing to apply to. */
    fun clear() { frames.clear(); objects = emptyList(); detectedAt = 0 }
    private fun advance(a: GrayFrame, b: GrayFrame, objects: List<LiveObject>) = objects.mapNotNull { o -> trackBox(a, b, o.box)?.let { o.copy(box = it) } }
    fun current(id: String): Boolean {
        val reference = frames.firstOrNull { it.id == id } ?: return false; val last = frames.lastOrNull() ?: return false
        return last.time - reference.time < 20000 && difference(reference, last) < 32
    }
}
