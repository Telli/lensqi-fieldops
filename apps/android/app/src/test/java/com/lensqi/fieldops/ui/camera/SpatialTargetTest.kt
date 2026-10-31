package com.lensqi.fieldops.ui.camera

import com.lensqi.fieldops.arcore.TargetState
import com.lensqi.fieldops.arcore.TargetView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SpatialTargetTest {
    private fun target(state: TargetState, ambiguous: Boolean = false, seen: Int = 0) = TargetView("a", "patch cable", "Patch cable", state, seen, ambiguous)

    @Test fun `the screen asks for the target in each state and says nothing while it is tracked`() {
        assertNull(targetNote(target(TargetState.Tracking), "Patch cable", true))
        assertEquals("Two patch cables fit here · tap the one you're working on", targetNote(target(TargetState.Tracking, ambiguous = true), null, true))
        assertEquals("Not seeing the patch cable · last matched 14s ago · keep it in view", targetNote(target(TargetState.Uncertain, seen = 14), null, true))
        assertEquals("Lost the patch cable · find it and tap it to confirm again", targetNote(target(TargetState.Lost), null, false))
        // After the shutter the AR session is new: the remembered part must be found and confirmed again.
        assertEquals("Find the patch cable again and tap it to confirm", targetNote(null, "Patch cable", true))
        assertEquals("Tap a part to hold it as the target", targetNote(null, null, true))
        assertNull(targetNote(null, null, false))
    }

    @Test fun `the arrow sits on the inset edge in the part's direction`() {
        assertEquals(960f to 500f, edgePoint(1.0, 0.0, 1000f, 1000f, 40f))
        assertEquals(500f to 40f, edgePoint(0.0, -0.3, 1000f, 1000f, 40f))
        // View units scale with the screen: a quarter view down for a full view right meets the side of a tall screen.
        val (x, y) = edgePoint(1.0, 0.25, 1000f, 2000f, 0f)!!
        assertEquals(1000f, x, 0.01f); assertEquals(1250f, y, 0.01f)
        assertNull(edgePoint(0.0, 0.0, 1000f, 1000f, 40f))
    }

    @Test fun `the still is checked against the server's part, else a target tracked at the shutter, else nothing`() {
        assertEquals("yellow cable at port 2", partToConfirm("yellow cable at port 2", target(TargetState.Tracking)))
        assertEquals("Patch cable", partToConfirm(null, target(TargetState.Tracking)))
        // A target that was uncertain, lost or ambiguous at the shutter is no basis for the question; a wide view goes on.
        assertNull(partToConfirm(null, target(TargetState.Uncertain)))
        assertNull(partToConfirm(" ", target(TargetState.Lost)))
        assertNull(partToConfirm(null, target(TargetState.Tracking, ambiguous = true)))
        assertNull(partToConfirm(null, null))
    }
}
