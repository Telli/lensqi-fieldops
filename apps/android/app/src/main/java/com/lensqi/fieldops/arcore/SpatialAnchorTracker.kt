package com.lensqi.fieldops.arcore

import com.lensqi.fieldops.domain.live.LiveBox
import com.lensqi.fieldops.domain.live.LiveObject
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 6DoF camera pose in ARCore right-handed world coordinates (X right, Y up, -Z forward in camera space; meters).
 */
data class Pose6DoF(
    val tx: Double = 0.0,
    val ty: Double = 0.0,
    val tz: Double = 0.0,
    val qx: Double = 0.0,
    val qy: Double = 0.0,
    val qz: Double = 0.0,
    val qw: Double = 1.0,
    /** Normalized focal lengths (fx / width, fy / height). Typical smartphone 60° HFOV ≈ 0.86. */
    val fx: Double = 0.86,
    val fy: Double = 1.15,
) {
    /** Transforms a point from camera-local coordinates (cx, cy, cz where -cz is forward depth) into world space. */
    fun cameraToWorld(cx: Double, cy: Double, cz: Double): Triple<Double, Double, Double> {
        val (rx, ry, rz) = rotateQuaternion(qx, qy, qz, qw, cx, cy, cz)
        return Triple(tx + rx, ty + ry, tz + rz)
    }

    /** Transforms a world-space point (wx, wy, wz) into camera-local coordinates (cx, cy, cz). */
    fun worldToCamera(wx: Double, wy: Double, wz: Double): Triple<Double, Double, Double> {
        // Inverse rotation uses the conjugate quaternion (-qx, -qy, -qz, qw).
        return rotateQuaternion(-qx, -qy, -qz, qw, wx - tx, wy - ty, wz - tz)
    }

    fun distanceMetersTo(wx: Double, wy: Double, wz: Double): Double =
        sqrt((wx - tx) * (wx - tx) + (wy - ty) * (wy - ty) + (wz - tz) * (wz - tz))

    companion object {
        private fun rotateQuaternion(
            qx: Double, qy: Double, qz: Double, qw: Double,
            vx: Double, vy: Double, vz: Double,
        ): Triple<Double, Double, Double> {
            val tx = 2.0 * (qy * vz - qz * vy)
            val ty = 2.0 * (qz * vx - qx * vz)
            val tz = 2.0 * (qx * vy - qy * vx)
            return Triple(
                vx + qw * tx + (qy * tz - qz * ty),
                vy + qw * ty + (qz * tx - qx * tz),
                vz + qw * tz + (qx * ty - qy * tx),
            )
        }
    }
}

/**
 * Snapshot of the camera's 6DoF pose and optional Depth API grid at the exact instant a frame (`frameId`) was sampled.
 * Used to unproject delayed SAM 3.1 2D detections from the historical viewpoint where the photo was taken.
 */
data class PoseSnapshot(
    val frameId: String,
    val timeMs: Long,
    val pose: Pose6DoF,
    val defaultDepthMeters: Double = 0.35,
    val depthGridMeters: FloatArray? = null,
    val gridWidth: Int = 0,
    val gridHeight: Int = 0,
) {
    /** Samples metric depth (in meters) at normalized image coordinates (u, v) in [0, 1]. */
    fun sampleDepthMeters(u: Double, v: Double): Double {
        val grid = depthGridMeters
        if (grid != null && gridWidth > 0 && gridHeight > 0 && grid.size >= gridWidth * gridHeight) {
            val gx = (u.coerceIn(0.0, 0.999) * gridWidth).toInt()
            val gy = (v.coerceIn(0.0, 0.999) * gridHeight).toInt()
            val d = grid[gy * gridWidth + gx].toDouble()
            if (d.isFinite() && d in 0.10..5.0) return d
        }
        return defaultDepthMeters.coerceIn(0.12, 5.0)
    }
}

/**
 * World-locked 3D anchor representing a physical equipment part segmented by SAM 3.1.
 */
data class WorldSpatialAnchor(
    val id: String,
    val concept: String,
    val label: String,
    val worldX: Double,
    val worldY: Double,
    val worldZ: Double,
    val worldWidthMeters: Double,
    val worldHeightMeters: Double,
    val confidence: Double? = null,
    val updatedAtMs: Long,
)

/**
 * Ring buffer of historical camera poses and world-locked 3D spatial anchors.
 *
 * Unlike 2D patch tracking (which loses an object as soon as the camera pans away), 3D anchors persist in world
 * coordinates and automatically re-project onto the viewfinder when the technician looks back at the equipment.
 */
class SpatialAnchorTracker(private val maxHistoryFrames: Int = 240, private val anchorTtlMs: Long = 60_000L) {
    private val history = ArrayDeque<PoseSnapshot>()
    private val anchorsByConcept = LinkedHashMap<String, WorldSpatialAnchor>()

    val activeAnchors: List<WorldSpatialAnchor>
        get() = anchorsByConcept.values.toList()

    fun clear() {
        history.clear()
        anchorsByConcept.clear()
    }

    /** Records the camera's 6DoF pose and depth map for a frame and returns currently visible 3D-anchored objects. */
    fun pushPose(snapshot: PoseSnapshot): List<LiveObject> {
        history.addLast(snapshot)
        while (history.size > maxHistoryFrames || snapshot.timeMs - history.first().timeMs > 24_000L) {
            history.removeFirst()
        }
        anchorsByConcept.entries.removeIf { (_, a) -> snapshot.timeMs - a.updatedAtMs > anchorTtlMs }
        return projectVisible(snapshot.pose)
    }

    /**
     * Unprojects 2D SAM detections from the historical pose at [frameId] into 3D world-locked anchors, then
     * projects them onto the latest camera pose.
     */
    fun seedFromSam(frameId: String, detected: List<LiveObject>): Boolean {
        val snapshot = history.firstOrNull { it.idMatches(frameId) } ?: return false
        for (obj in detected) {
            val b = obj.box
            if (!b.isValidNormalized()) continue
            val u = (b.left + b.right) * 0.5
            val v = (b.top + b.bottom) * 0.5
            val depth = snapshot.sampleDepthMeters(u, v)
            // Pinhole unprojection in ARCore camera space: +X right, +Y up, -Z forward.
            val camX = (u - 0.5) * depth / snapshot.pose.fx
            val camY = (0.5 - v) * depth / snapshot.pose.fy
            val camZ = -depth
            val (wx, wy, wz) = snapshot.pose.cameraToWorld(camX, camY, camZ)
            val worldW = max(0.03, (b.right - b.left) * depth / snapshot.pose.fx)
            val worldH = max(0.03, (b.bottom - b.top) * depth / snapshot.pose.fy)
            val key = obj.concept.lowercase()
            anchorsByConcept[key] = WorldSpatialAnchor(
                id = obj.id,
                concept = obj.concept,
                label = obj.label,
                worldX = wx,
                worldY = wy,
                worldZ = wz,
                worldWidthMeters = worldW,
                worldHeightMeters = worldH,
                confidence = obj.confidence,
                updatedAtMs = snapshot.timeMs,
            )
        }
        return true
    }

    /** Projects all active 3D world anchors into the given camera [pose], computing metric distance in centimeters. */
    fun projectVisible(pose: Pose6DoF): List<LiveObject> {
        val visible = mutableListOf<LiveObject>()
        for (anchor in anchorsByConcept.values) {
            val (camX, camY, camZ) = pose.worldToCamera(anchor.worldX, anchor.worldY, anchor.worldZ)
            val forwardDepth = -camZ
            if (forwardDepth < 0.08 || forwardDepth > 6.0) continue
            val u = 0.5 + (camX / forwardDepth) * pose.fx
            val v = 0.5 - (camY / forwardDepth) * pose.fy
            val halfW = (anchor.worldWidthMeters / forwardDepth) * pose.fx * 0.5
            val halfH = (anchor.worldHeightMeters / forwardDepth) * pose.fy * 0.5
            val left = (u - halfW).coerceIn(0.0, 1.0)
            val right = (u + halfW).coerceIn(0.0, 1.0)
            val top = (v - halfH).coerceIn(0.0, 1.0)
            val bottom = (v + halfH).coerceIn(0.0, 1.0)
            // Keep only anchors whose projected box overlaps the current camera frustum.
            if (right - left < 0.015 || bottom - top < 0.015 || u < -0.15 || u > 1.15 || v < -0.15 || v > 1.15) continue
            val euclideanMeters = pose.distanceMetersTo(anchor.worldX, anchor.worldY, anchor.worldZ)
            val cm = (euclideanMeters * 100.0).roundToInt().coerceAtLeast(1)
            visible.add(
                LiveObject(
                    id = anchor.id,
                    concept = anchor.concept,
                    label = anchor.label,
                    box = LiveBox(left, top, right, bottom),
                    confidence = anchor.confidence,
                    distanceCm = cm,
                    spatialAnchored = true,
                ),
            )
        }
        return visible
    }

    private fun PoseSnapshot.idMatches(id: String) = frameId == id
    private fun LiveBox.isValidNormalized() =
        listOf(left, top, right, bottom).all { it.isFinite() } && left >= 0.0 && top >= 0.0 && right <= 1.0 && bottom <= 1.0 && right > left && bottom > top

    companion object {
        /**
         * Generates calibrated metric distance coaching (in cm) when a 3D-anchored part has a measured camera-to-anchor distance.
         * Optimal close-up inspection window for switch ports & cable labels is 20–40 cm.
         */
        fun metricDistanceGuidance(objects: List<LiveObject>, targetConcept: String? = null): String? {
            val anchored = objects.filter { it.spatialAnchored && it.distanceCm != null }
            if (anchored.isEmpty()) return null
            val primary = targetConcept?.let { tc -> anchored.firstOrNull { it.concept.equals(tc, ignoreCase = true) } }
                ?: anchored.minByOrNull { it.distanceCm ?: Int.MAX_VALUE }
                ?: return null
            val cm = primary.distanceCm ?: return null
            val name = primary.label.lowercase()
            return when {
                cm > 45 -> "Move ~${cm - 30} cm closer to the $name ($cm cm away)."
                cm < 16 -> "Move back ~${22 - cm} cm from the $name ($cm cm) so the lens can focus."
                else -> "Optimal distance ($cm cm) · 3D anchor locked on $name. Capture when ready."
            }
        }
    }
}
