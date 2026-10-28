package com.lensqi.fieldops.arcore

import com.lensqi.fieldops.domain.live.LiveBox
import com.lensqi.fieldops.domain.live.LiveObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin

class SpatialAnchorTrackerTest {
    private val sampleCable = LiveObject(
        id = "cable-1",
        concept = "yellow ethernet cable",
        label = "Yellow cable",
        box = LiveBox(left = 0.35, top = 0.40, right = 0.65, bottom = 0.60),
        confidence = 0.94,
    )

    @Test
    fun `unprojects 2D SAM box from historical pose and depth into 3D world anchor`() {
        val tracker = SpatialAnchorTracker()
        val initialPose = Pose6DoF(tx = 0.0, ty = 0.0, tz = 0.0)
        tracker.pushPose(PoseSnapshot(frameId = "frame-1", timeMs = 1000L, pose = initialPose, defaultDepthMeters = 0.32))
        assertTrue(tracker.seedFromSam("frame-1", listOf(sampleCable)))

        val projected = tracker.projectVisible(initialPose)
        assertEquals(1, projected.size)
        val obj = projected.single()
        assertTrue(obj.spatialAnchored)
        assertEquals(32, obj.distanceCm)
        assertEquals(sampleCable.box.left, obj.box.left, 0.01)
        assertEquals(sampleCable.box.right, obj.box.right, 0.01)
    }

    @Test
    fun `world-locked 3D anchor survives full out-of-frame camera pan and reappears on return`() {
        val tracker = SpatialAnchorTracker()
        val pose0 = Pose6DoF()
        tracker.pushPose(PoseSnapshot(frameId = "frame-1", timeMs = 1000L, pose = pose0, defaultDepthMeters = 0.35))
        tracker.seedFromSam("frame-1", listOf(sampleCable))

        // Rotate camera 90 degrees around Y axis (looking completely away from the equipment rack).
        val halfAngle = Math.toRadians(90.0) / 2.0
        val panAwayPose = Pose6DoF(qx = 0.0, qy = sin(halfAngle), qz = 0.0, qw = cos(halfAngle))
        val whileLookingAway = tracker.pushPose(PoseSnapshot(frameId = "frame-2", timeMs = 2000L, pose = panAwayPose))
        assertTrue(whileLookingAway.isEmpty(), "Anchor behind/beside camera frustum must not render on screen")
        assertEquals(1, tracker.activeAnchors.size, "World-locked anchor must remain alive in 3D space")

        // Pan back to original orientation: anchor reappears at exact position without a new SAM call.
        val returned = tracker.pushPose(PoseSnapshot(frameId = "frame-3", timeMs = 3000L, pose = pose0))
        assertEquals(1, returned.size)
        assertEquals(35, returned.single().distanceCm)
        assertEquals(sampleCable.box.left, returned.single().box.left, 0.01)
    }

    @Test
    fun `metric distance coaching guides technician into 20-40cm window as camera moves closer`() {
        val tracker = SpatialAnchorTracker()
        val poseFar = Pose6DoF(tz = 0.0)
        // Anchor seeded at 55 cm depth (-0.55m on Z).
        tracker.pushPose(PoseSnapshot(frameId = "f-far", timeMs = 1000L, pose = poseFar, defaultDepthMeters = 0.55))
        tracker.seedFromSam("f-far", listOf(sampleCable))

        val farObjects = tracker.projectVisible(poseFar)
        assertEquals(55, farObjects.single().distanceCm)
        val farAdvice = SpatialAnchorTracker.metricDistanceGuidance(farObjects)
        assertNotNull(farAdvice)
        assertTrue(farAdvice!!.contains("closer"), "Expected move-closer coaching, got: $farAdvice")

        // Move camera 25 cm forward along -Z (now 30 cm from the anchor).
        val poseOptimal = Pose6DoF(tz = -0.25)
        val optimalObjects = tracker.projectVisible(poseOptimal)
        assertEquals(30, optimalObjects.single().distanceCm)
        assertTrue(optimalObjects.single().box.right - optimalObjects.single().box.left > farObjects.single().box.right - farObjects.single().box.left,
            "Projected 3D box must scale larger as the camera moves closer")
        val optimalAdvice = SpatialAnchorTracker.metricDistanceGuidance(optimalObjects)
        assertNotNull(optimalAdvice)
        assertTrue(optimalAdvice!!.contains("Optimal distance (30 cm)"), "Expected optimal-distance coaching, got: $optimalAdvice")
    }
}
