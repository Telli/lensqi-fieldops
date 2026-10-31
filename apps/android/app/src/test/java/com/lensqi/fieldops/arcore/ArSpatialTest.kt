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

    @Test fun `coaching uses the measured distance against the task's range and stays silent without one`() {
        val near = LiveObject("w", "c", "Yellow cable", centre, distanceCm = 72, spatialAnchored = true)
        assertEquals("Move about 37 cm closer to the yellow cable (estimated 72 cm away).", distanceAdvice(listOf(near), framingCm = 25..45))
        assertNull(distanceAdvice(listOf(near.copy(distanceCm = null, spatialAnchored = false)), framingCm = 25..45))
        // Without a range only the lens's near limit is coached: no universal "30 cm".
        assertNull(distanceAdvice(listOf(near)))
        assertEquals("Move back about 10 cm from the yellow cable so the lens can focus (estimated 12 cm away).", distanceAdvice(listOf(near.copy(distanceCm = 12))))
    }

    @Test fun `a step's target part is coached on, not a nearer one`() {
        val plug = LiveObject("p", "drain plug", "Drain plug", centre, distanceCm = 60, spatialAnchored = true)
        val pan = LiveObject("q", "oil pan", "Oil pan", centre, distanceCm = 20, spatialAnchored = true)
        assertEquals("Move about 25 cm closer to the drain plug (estimated 60 cm away).", distanceAdvice(listOf(pan, plug), "Drain Plug", 20..50))
        assertEquals("Good distance from the oil pan (estimated 20 cm). Capture when ready.", distanceAdvice(listOf(pan, plug), framingCm = 15..30))
        assertNull(distanceAdvice(listOf(pan), "drain plug", 20..50))
    }

    @Test fun `no distance is coached for a part overhead, far away or uncertain`() {
        // Phone run 2: "Move about 250 cm closer to the ceiling detector" for a part 2.8 m away on the ceiling.
        val ceiling = LiveObject("d", "smoke detector", "Ceiling detector", centre, distanceCm = 280, spatialAnchored = true, aboveCameraCm = 120)
        assertNull(distanceAdvice(listOf(ceiling), "smoke detector", 25..45))
        assertNull(distanceAdvice(listOf(ceiling.copy(distanceCm = 60)), "smoke detector", 25..45))
        assertNull(distanceAdvice(listOf(ceiling.copy(aboveCameraCm = 0)), "smoke detector", 25..45))
        val plug = LiveObject("p", "drain plug", "Drain plug", centre, distanceCm = 60, spatialAnchored = true, target = true, uncertain = true)
        val pan = LiveObject("q", "oil pan", "Oil pan", centre, distanceCm = 60, spatialAnchored = true)
        // An uncertain confirmed part silences coaching rather than handing it to another part.
        assertNull(distanceAdvice(listOf(plug, pan), framingCm = 20..50))
    }

    @Test fun `upright image points map back to the sensor image`() {
        assertEquals(0.3 to 0.8, uprightToSensor(0.2, 0.3, 90))
        assertEquals(0.8 to 0.7, uprightToSensor(0.2, 0.3, 180))
        assertEquals(0.7 to 0.2, uprightToSensor(0.2, 0.3, 270))
        val map = ViewMap(-0.1, 0.0, 1.2, 0.0, 0.0, 1.0)
        assertEquals(LiveBox(0.0, 0.2, 0.62, 0.4), map.box(LiveBox(0.0, 0.2, 0.6, 0.4))!!.let { b -> LiveBox(b.left, b.top, Math.round(b.right * 100) / 100.0, b.bottom) })
    }

    @Test fun `a ceiling part seen again with noisy depth stays one label and settles`() {
        val anchors = SpatialAnchors()
        anchors.seed(view(), depthEverywhere(2.8), listOf(part("a", centre, "smoke detector")), 0) { FakeAnchor(it) }
        // ARCore's depth on a plain ceiling jumps by tens of centimetres; the part projects inside the new box, so it is the same.
        anchors.seed(view(), depthEverywhere(3.2), listOf(part("b", LiveBox(0.42, 0.41, 0.62, 0.61), "smoke detector")), 4000) { FakeAnchor(it) }
        anchors.seed(view(x = 0.05), depthEverywhere(2.9), listOf(part("c", LiveBox(0.39, 0.4, 0.59, 0.6), "smoke detector")), 8000) { FakeAnchor(it) }
        val one = anchors.visible(view(), 8000).single()
        assertTrue(one.distanceCm!! in 280..320, "averaged, not the last noisy reading: ${one.distanceCm}")
    }

    @Test fun `one part found under two names is one label with the step's name`() {
        val anchors = SpatialAnchors().apply { preferredConcept = "smoke detector" }
        anchors.seed(view(), depthEverywhere(2.5), listOf(part("a", centre, "ceiling detector")), 0) { FakeAnchor(it) }
        anchors.seed(view(), depthEverywhere(2.5), listOf(part("b", LiveBox(0.41, 0.41, 0.61, 0.61), "smoke detector")), 4000) { FakeAnchor(it) }
        assertEquals(listOf("smoke detector"), anchors.visible(view(), 4000).map { it.concept })
        // In one frame too: overlapping boxes under two names are one part, the step's name kept.
        val merged = onePerPart(listOf(part("x", centre, "ceiling detector").copy(confidence = 0.95) to centre, part("y", centre, "smoke detector").copy(confidence = 0.6) to centre)) { it == "smoke detector" }
        assertEquals(listOf("smoke detector"), merged.map { it.first.concept })
    }

    @Test fun `a sub-part inside a part stays its own label`() {
        val button = LiveBox(0.48, 0.48, 0.52, 0.52)
        val kept = onePerPart(listOf(part("d", centre, "smoke detector") to centre, part("t", button, "test button") to button))
        assertEquals(listOf("smoke detector", "test button"), kept.map { it.first.concept }.sorted())
    }

    @Test fun `at most two labels are drawn, the step's part first, and the rest are counted`() {
        val objects = listOf(
            part("a", LiveBox(0.0, 0.0, 0.1, 0.1), "closet door").copy(distanceCm = 150),
            part("b", LiveBox(0.4, 0.4, 0.6, 0.6), "smoke detector").copy(distanceCm = 290),
            part("c", LiveBox(0.41, 0.41, 0.61, 0.61), "smoke detector").copy(distanceCm = 300),   // its 2D track: the same part
            part("d", LiveBox(0.8, 0.8, 0.9, 0.9), "shelf").copy(distanceCm = 200),
            part("e", LiveBox(0.7, 0.1, 0.8, 0.2), "lamp"),
        )
        val (shown, more) = partsToShow(objects, "smoke detector")
        assertEquals(listOf("b", "a"), shown.map { it.id })
        assertEquals(2, more)
    }

    @Test fun `a confirmed part covered by a hand is uncertain, not removed, and comes back when seen`() {
        val anchors = SpatialAnchors()
        anchors.seed(view(), depthEverywhere(0.5), listOf(part("a", centre)), 0) { FakeAnchor(it) }
        val id = anchors.visible(view(), 0).single().anchorId!!
        assertTrue(anchors.confirm(id, 1000))
        assertEquals(TargetState.Tracking, anchors.target(view(), 1000)!!.state)
        // Looked straight at it and nothing found: an unconfirmed part would be removed here.
        anchors.seed(view(), depthEverywhere(0.5), emptyList(), 4000) { FakeAnchor(it) }
        assertEquals(1, anchors.size)
        assertEquals(TargetState.Uncertain, anchors.target(view(), 4000)!!.state)
        assertTrue(anchors.visible(view(), 4000).single().uncertain)
        anchors.seed(view(), depthEverywhere(0.5), listOf(part("b", centre)), 8000) { FakeAnchor(it) }
        assertEquals(TargetState.Tracking, anchors.target(view(), 8000)!!.state)
        assertEquals(id, anchors.target(view(), 8000)!!.id)
    }

    @Test fun `a confirmed part not matched for a minute is lost and must be confirmed again`() {
        val anchors = SpatialAnchors(uncertainMs = 12_000, lostMs = 60_000)
        anchors.seed(view(), depthEverywhere(0.5), listOf(part("a", centre)), 0) { FakeAnchor(it) }
        anchors.confirm(anchors.visible(view(), 0).single().anchorId!!, 0)
        // Outlives the 20 s expiry of unconfirmed labels, faded once it is not matched.
        assertEquals(TargetState.Uncertain, anchors.target(view(), 30_000)!!.state)
        assertEquals(TargetState.Lost, anchors.target(view(), 60_001)!!.state)
        assertEquals(0, anchors.size)
        // While ARCore is not tracking the camera, a target is uncertain, never shown as tracked.
        val again = SpatialAnchors()
        again.seed(view(), depthEverywhere(0.5), listOf(part("a", centre)), 0) { FakeAnchor(it) }
        again.confirm(again.visible(view(), 0).single().anchorId!!, 0)
        assertEquals(TargetState.Uncertain, again.target(null, 100)!!.state)
    }

    @Test fun `two boxes of the target's name around it ask the technician instead of picking one`() {
        val anchors = SpatialAnchors()
        anchors.seed(view(), depthEverywhere(0.5), listOf(part("a", centre)), 0) { FakeAnchor(it) }
        anchors.confirm(anchors.visible(view(), 0).single().anchorId!!, 0)
        val before = anchors.target(view(), 0)!!
        // Two identical cables side by side whose boxes both reach over the target's position.
        val left = LiveBox(0.30, 0.40, 0.52, 0.60); val right = LiveBox(0.48, 0.40, 0.70, 0.60)
        anchors.seed(view(), depthEverywhere(0.5), listOf(part("l", left), part("r", right)), 4000) { FakeAnchor(it) }
        val after = anchors.target(view(), 4000)!!
        assertTrue(after.ambiguous)
        assertEquals(before.id, after.id)
        // Confirming one of them settles it.
        val pick = anchors.visible(view(), 4000).first { !it.target }.anchorId!!
        anchors.confirm(pick, 5000)
        assertEquals(pick, anchors.target(view(), 5000)!!.id); assertTrue(!anchors.target(view(), 5000)!!.ambiguous)
    }

    @Test fun `noisy depth on the confirmed part neither splits it nor replaces its anchor`() {
        // Phone run 2 showed one ceiling detector as three labels at 280, 320 and 385 cm.
        var made = 0
        val anchors = SpatialAnchors()
        anchors.seed(view(), depthEverywhere(2.8), listOf(part("a", centre, "smoke detector")), 0) { made++; FakeAnchor(it) }
        anchors.confirm(anchors.visible(view(), 0).single().anchorId!!, 0)
        anchors.seed(view(), depthEverywhere(2.9), listOf(part("b", LiveBox(0.41, 0.4, 0.61, 0.6), "smoke detector")), 4000) { made++; FakeAnchor(it) }
        assertEquals(1, made, "a small shift moves the label, not the anchor")
        // Same name, beside the target, and the target is matched by the first box: the second is the same part split.
        anchors.seed(view(), depthEverywhere(3.2), listOf(part("c", centre, "smoke detector"), part("d", LiveBox(0.62, 0.40, 0.70, 0.48), "smoke detector")), 8000) { made++; FakeAnchor(it) }
        assertEquals(1, anchors.visible(view(), 8000).size)
    }

    @Test fun `the way back points to the side the part is on, and says turn around when it is behind`() {
        val anchors = SpatialAnchors()
        anchors.seed(view(), depthEverywhere(1.0), listOf(part("a", centre)), 0) { FakeAnchor(it) }
        anchors.confirm(anchors.visible(view(), 0).single().anchorId!!, 0)
        assertNull(anchors.target(view(), 0)!!.cue)
        // Turned 60° left (counter-clockwise about +Y): the part is ahead but off the right edge.
        val right = anchors.target(view(yawDeg = 60.0), 0)!!.cue!!
        assertTrue(right.dx > 0 && !right.behind, "$right")
        val behind = anchors.target(view(yawDeg = 180.0), 0)!!.cue!!
        assertTrue(behind.behind, "$behind")
    }

    @Test fun `the floor is forgotten when tracking stays lost`() {
        val floor = FloorReference(graceMs = 3000)
        floor.set(0.0)
        floor.tracking(false, 1000); floor.tracking(false, 3500); assertEquals(0.0, floor.y)
        floor.tracking(true, 3600); floor.tracking(false, 4000); floor.tracking(false, 7001); assertNull(floor.y)
    }

    @Test fun `a live 2D track keeps the anchor's height and identity`() {
        val anchored = LiveObject("w", "smoke detector", "Smoke detector", LiveBox(0.40, 0.40, 0.60, 0.60), distanceCm = 120, spatialAnchored = true,
            heightAboveFloorCm = 238, anchorId = "w", target = true)
        val fused = fuseTracks(listOf(LiveObject("t", "smoke detector", "Smoke detector", LiveBox(0.41, 0.41, 0.61, 0.61))), listOf(anchored)).single()
        assertEquals(238, fused.heightAboveFloorCm); assertEquals("w", fused.anchorId); assertTrue(fused.target)
    }

    @Test fun `height above the floor comes from the anchor and the floor plane`() {
        val anchors = SpatialAnchors()
        // Camera 1.5 m up, looking level; the part is 1 m ahead at the camera's height.
        anchors.seed(view(y = 1.5), depthEverywhere(1.0), listOf(part("a", centre)), 0) { FakeAnchor(it) }
        assertNull(anchors.visible(view(y = 1.5), 0).single().heightAboveFloorCm)
        val h = anchors.visible(view(y = 1.5), 0, floorY = 0.0).single().heightAboveFloorCm!!
        assertTrue(h in 145..155, "about 150 cm, got $h")
    }
}

