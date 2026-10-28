package com.lensqi.fieldops.arcore

import com.lensqi.fieldops.domain.live.LiveBox
import com.lensqi.fieldops.domain.live.LiveObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.tan

/** Geometry is checked with the matrices ARCore returns (column-major GL), not with assumed focal lengths or depths. */
class ArSpatialTest {
    private class FakeAnchor(var at: DoubleArray?) : WorldAnchor {
        var detached = false
        override fun position() = if (detached) null else at
        override val stopped get() = detached
        override fun detach() { detached = true }
    }

    /** GL perspective with a small principal-point offset, as ARCore produces for a real lens. */
    private fun projection(fovYDeg: Double = 60.0, aspect: Double = 0.5625, cx: Float = 0.02f, cy: Float = -0.01f): FloatArray {
        val f = (1 / tan(Math.toRadians(fovYDeg) / 2)).toFloat(); val n = 0.05f; val far = 20f
        return FloatArray(16).apply {
            this[0] = f / aspect.toFloat(); this[5] = f; this[8] = cx; this[9] = cy
            this[10] = (far + n) / (n - far); this[11] = -1f; this[14] = 2 * far * n / (n - far)
        }
    }

    /** Camera at [x, y, z] looking down −Z (ARCore's convention), optionally turned [yawDeg] about +Y. */
    private fun view(x: Double = 0.0, y: Double = 0.0, z: Double = 0.0, yawDeg: Double = 0.0): ArCameraView {
        val a = Math.toRadians(yawDeg); val c = kotlin.math.cos(a).toFloat(); val s = kotlin.math.sin(a).toFloat()
        val m = floatArrayOf(c, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, c, 0f, x.toFloat(), y.toFloat(), z.toFloat(), 1f)
        return ArCameraView(m, projection(), ViewMap.Identity, doubleArrayOf(x, y, z))
    }

    private fun depthEverywhere(m: Double) = DepthSamples(DepthSource.DepthApi, (0 until 20).flatMap { gx -> (0 until 20).map { gy -> DepthSample((gx + .5) / 20, (gy + .5) / 20, m) } })
    private fun part(id: String, box: LiveBox, concept: String = "yellow ethernet cable") = LiveObject(id, concept, "Yellow cable", box, 0.9)
    private val centre = LiveBox(0.4, 0.4, 0.6, 0.6)

    @Test fun `a view point unprojected at a depth projects back to the same point`() {
        val v = view(0.3, 1.2, -0.5, yawDeg = 25.0)
        val p = v.project(v.unproject(0.27, 0.71, 0.62))!!
        assertEquals(0.27, p.x, 1e-6); assertEquals(0.71, p.y, 1e-6); assertEquals(0.62, p.forwardMeters, 1e-6)
    }

    @Test fun `distance is the measured depth, not a constant`() {
        for (d in listOf(0.25, 0.6, 1.5)) {
            val anchors = SpatialAnchors(); val v = view()
            assertEquals(1, anchors.seed(v, depthEverywhere(d), listOf(part("a", centre)), 0) { FakeAnchor(it) })
            val cm = anchors.visible(v, 0).single().distanceCm!!
            assertTrue(cm in (d * 100).toInt() - 2..(d * 100).toInt() + 2, "expected about ${d * 100} cm, got $cm")
        }
    }

    @Test fun `no measured depth inside the box means no anchor and no distance`() {
        val anchors = SpatialAnchors()
        val farAway = DepthSamples(DepthSource.HitTest, listOf(DepthSample(0.05, 0.05, 0.8)))
        assertEquals(0, anchors.seed(view(), farAway, listOf(part("a", centre)), 0) { FakeAnchor(it) })
        assertTrue(anchors.visible(view(), 0).isEmpty())
        assertNull(DepthSamples(DepthSource.HitTest, emptyList()).forwardIn(centre))
    }

    @Test fun `two parts with the same concept stay two anchors`() {
        val anchors = SpatialAnchors()
        anchors.seed(view(), depthEverywhere(0.5), listOf(part("a", LiveBox(0.1, 0.4, 0.3, 0.6)), part("b", LiveBox(0.7, 0.4, 0.9, 0.6))), 0) { FakeAnchor(it) }
        assertEquals(2, anchors.visible(view(), 0).size)
    }

    @Test fun `the anchor is world-locked when the camera moves`() {
        val anchors = SpatialAnchors()
        anchors.seed(view(), depthEverywhere(0.5), listOf(part("a", centre)), 0) { FakeAnchor(it) }
        // Step 10 cm right: the part should move left on screen and stay 50 cm ahead.
        val moved = anchors.visible(view(x = 0.10), 100).single()
        assertTrue((moved.box.left + moved.box.right) / 2 < 0.45, "box should shift left, was ${moved.box}")
        assertTrue(moved.distanceCm!! in 50..52, "distance ${moved.distanceCm}")
    }

    @Test fun `seeing the same part again refreshes its anchor instead of adding one`() {
        val anchors = SpatialAnchors()
        anchors.seed(view(), depthEverywhere(0.5), listOf(part("a", centre)), 0) { FakeAnchor(it) }
        val id = anchors.visible(view(), 0).single().id
        anchors.seed(view(), depthEverywhere(0.52), listOf(part("other-id", LiveBox(0.41, 0.4, 0.61, 0.6))), 4000) { FakeAnchor(it) }
        assertEquals(listOf(id), anchors.visible(view(), 4000).map { it.id })
    }

    @Test fun `a part looked at and not found again is removed, one out of view is kept`() {
        val anchors = SpatialAnchors()
        anchors.seed(view(), depthEverywhere(0.5), listOf(part("a", centre)), 0) { FakeAnchor(it) }
        // Turned away: the anchor is outside this detection frame, so a miss proves nothing.
        anchors.seed(view(yawDeg = 90.0), depthEverywhere(0.5), emptyList(), 2000) { FakeAnchor(it) }
        assertEquals(1, anchors.size)
        // Looking straight at it with no detection: the part moved or was removed.
        anchors.seed(view(), depthEverywhere(0.5), emptyList(), 4000) { FakeAnchor(it) }
        assertEquals(0, anchors.size)
    }

    @Test fun `unconfirmed anchors expire`() {
        val anchors = SpatialAnchors(ttlMs = 20_000)
        anchors.seed(view(), depthEverywhere(0.5), listOf(part("a", centre)), 0) { FakeAnchor(it) }
        assertEquals(1, anchors.visible(view(), 19_000).size)
        assertTrue(anchors.visible(view(), 20_001).isEmpty())
    }

    @Test fun `a live 2D track keeps its box and gains the anchor distance`() {
        val anchored = LiveObject("w", "yellow ethernet cable", "Yellow cable", LiveBox(0.40, 0.40, 0.60, 0.60), distanceCm = 48, spatialAnchored = true, seenSecondsAgo = 3)
        val track = LiveObject("t", "yellow ethernet cable", "Yellow cable", LiveBox(0.42, 0.41, 0.62, 0.61))
        val fused = fuseTracks(listOf(track), listOf(anchored)).single()
        assertEquals(track.box, fused.box); assertEquals(48, fused.distanceCm); assertNull(fused.seenSecondsAgo)
        assertEquals(3, fuseTracks(emptyList(), listOf(anchored)).single().seenSecondsAgo)
    }

    @Test fun `coaching uses the measured distance and stays silent without one`() {
        val near = LiveObject("w", "c", "Yellow cable", centre, distanceCm = 72, spatialAnchored = true)
        assertEquals("Move about 42 cm closer to the yellow cable (about 72 cm away).", distanceAdvice(listOf(near)))
        assertNull(distanceAdvice(listOf(near.copy(distanceCm = null, spatialAnchored = false))))
    }

    @Test fun `upright image points map back to the sensor image`() {
        assertEquals(0.3 to 0.8, uprightToSensor(0.2, 0.3, 90))
        assertEquals(0.8 to 0.7, uprightToSensor(0.2, 0.3, 180))
        assertEquals(0.7 to 0.2, uprightToSensor(0.2, 0.3, 270))
        val map = ViewMap(-0.1, 0.0, 1.2, 0.0, 0.0, 1.0)
        assertEquals(LiveBox(0.0, 0.2, 0.62, 0.4), map.box(LiveBox(0.0, 0.2, 0.6, 0.4))!!.let { b -> LiveBox(b.left, b.top, Math.round(b.right * 100) / 100.0, b.bottom) })
    }
}
