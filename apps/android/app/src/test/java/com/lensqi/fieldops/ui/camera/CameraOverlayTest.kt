package com.lensqi.fieldops.ui.camera

import com.lensqi.fieldops.data.api.TaskCaution
import com.lensqi.fieldops.data.api.TaskStep
import com.lensqi.fieldops.domain.live.GrayFrame
import com.lensqi.fieldops.domain.live.LiveBox
import com.lensqi.fieldops.domain.live.LiveObject
import com.lensqi.fieldops.ui.task.safetySummary
import kotlinx.coroutines.awaitCancellation
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A question carries what the camera sees now, and what is drawn over the picture stays readable and on screen. */
class CameraOverlayTest {
    private val bounds = LabelRect(10f, 60f, 400f, 500f)

    @Test fun `a view is kept about once a second and never handed over stale`() {
        val frames = ViewFrames(intervalMs = 1000, maxAgeMs = 5000)
        assertTrue(frames.due(0), "nothing kept yet")
        assertNull(frames.latest(0))
        val first = byteArrayOf(1)
        frames.store(first, 100)
        assertFalse(frames.due(600))
        assertTrue(frames.due(1100))
        assertArrayEquals(first, frames.latest(5100), "still recent enough")
        assertNull(frames.latest(5101), "older than that is no longer what the camera sees")
    }

    @Test fun `live assist refreshes the view for a question between uploads`() {
        // Analysis never answers, so nothing more is uploaded after the first frame; the view must keep up anyway.
        val session = LiveCameraSession(analyze = { _, _, _ -> awaitCancellation() })
        var encoded = 0
        fun frame(at: Long) = session.analyse(GrayFrame("f$at", at, 2, 2, IntArray(4) { 120 }), null) { encoded++; byteArrayOf(at.toByte()) }
        assertNull(session.latestJpeg(now = 1_000), "no frame seen yet")
        frame(1_000)
        assertEquals(1, encoded)
        frame(1_500)
        assertEquals(1, encoded, "not due: nothing encoded")
        frame(2_100)
        assertEquals(2, encoded, "due again, though no upload is")
        assertArrayEquals(byteArrayOf(2_100L.toByte()), session.latestJpeg(now = 2_200))
        session.close()
    }

    @Test fun `a label goes above its part when there is room`() {
        val part = LabelRect(100f, 200f, 200f, 300f)
        val label = placeLabel(part, 120f, 40f, bounds, 8f)
        assertEquals(LabelRect(100f, 152f, 220f, 192f), label)
    }

    @Test fun `near the top it goes below, and it never leaves the visible picture`() {
        val high = placeLabel(LabelRect(100f, 62f, 200f, 120f), 120f, 40f, bounds, 8f)
        assertEquals(128f, high.top)
        val wide = placeLabel(LabelRect(350f, 200f, 395f, 300f), 300f, 40f, bounds, 8f)
        assertEquals(100f, wide.left, "pushed in from the right edge")
        assertEquals(400f, wide.right)
        // A part filling the picture leaves no room outside it: the label sits just inside, not off screen.
        val filling = placeLabel(LabelRect(10f, 60f, 400f, 500f), 120f, 40f, bounds, 8f)
        assertEquals(68f, filling.top)
    }

    @Test fun `a label stays above the controls that cover the bottom of the picture`() {
        val covered = LabelRect(10f, 60f, 400f, 300f)
        val low = placeLabel(LabelRect(100f, 280f, 200f, 480f), 120f, 40f, covered, 8f)
        assertTrue(low.bottom <= covered.bottom)
    }

    @Test fun `a label stays clear of the notes under the header and of the controls`() {
        val image = LabelRect(0f, 0f, 400f, 600f)
        val visible = labelBounds(image, clearTop = 100f, clearBottom = 450f, edge = 4f)
        assertEquals(LabelRect(4f, 104f, 396f, 446f), visible)
        // Right under the note: there is no room above the part, so the label goes below it instead of under the note.
        val label = placeLabel(LabelRect(100f, 110f, 200f, 200f), 120f, 40f, visible, 8f)
        assertEquals(208f, label.top)
    }

    @Test fun `two labels do not land on each other`() {
        val part = LabelRect(100f, 200f, 200f, 300f)
        val first = placeLabel(part, 120f, 40f, bounds, 8f)
        val second = placeLabel(part, 120f, 40f, bounds, 8f, listOf(first))
        assertFalse(first.overlaps(second))
        assertEquals(308f, second.top, "the next place: below the part")
    }

    private val detector = LiveObject("1", "smoke detector", "smoke detector", LiveBox(0.4, 0.3, 0.6, 0.5))

    @Test fun `an AI label says it is a suggestion, and a confirmed part is the person's`() {
        assertEquals(PartLabel("Smoke detector", "AI suggestion"), partLabel(detector))
        assertEquals(PartLabel("Smoke detector", "Your target"), partLabel(detector.copy(target = true)))
    }

    @Test fun `measurements go on the second line, and an uncertain target gives no distance`() {
        assertEquals("AI suggestion · est. 85 cm away · 2.4 m up", partLabel(detector.copy(distanceCm = 85, heightAboveFloorCm = 240)).detail)
        assertEquals("Your target · last seen 3 s ago", partLabel(detector.copy(target = true, uncertain = true, distanceCm = 85, seenSecondsAgo = 3)).detail)
    }

    @Test fun `a part held still does not shiver, a real move is followed and a jump is taken at once`() {
        val smoother = BoxSmoother(deadZone = 0.004, follow = 0.5, snap = 0.15)
        val start = detector.box
        assertEquals(start, smoother.smooth(listOf(detector)).single().box, "first sight: as tracked")
        val noise = detector.copy(box = LiveBox(0.402, 0.301, 0.602, 0.499))
        assertEquals(start, smoother.smooth(listOf(noise)).single().box, "noise is ignored")
        val moved = detector.copy(box = LiveBox(0.44, 0.34, 0.64, 0.54))
        val followed = smoother.smooth(listOf(moved)).single().box
        assertEquals(0.42, followed.left, 1e-9, "halfway there")
        val jump = detector.copy(box = LiveBox(0.1, 0.1, 0.3, 0.3))
        assertEquals(jump.box, smoother.smooth(listOf(jump)).single().box)
    }

    @Test fun `a part that left and came back starts from where it is now`() {
        val smoother = BoxSmoother()
        smoother.smooth(listOf(detector))
        smoother.smooth(emptyList())
        val back = detector.copy(box = LiveBox(0.45, 0.35, 0.65, 0.55))
        assertEquals(back.box, smoother.smooth(listOf(back)).single().box)
    }

    private val step = TaskStep("s1", "Locate", "Find the detector.", handsOn = false)

    @Test fun `the safety line leads with the stop condition and counts the rest`() {
        assertNull(safetySummary(step))
        assertEquals("Stop if it is cracked", safetySummary(step.copy(stopIf = listOf("it is cracked"))))
        val full = step.copy(stopIf = listOf("it is cracked", "it is loose"), cautions = listOf(TaskCaution("Warning", "Mind the ladder")))
        assertEquals("Stop if it is cracked · +2 more", safetySummary(full))
        val caution = safetySummary(step.copy(cautions = listOf(TaskCaution("Warning", "Mind the ladder"))))
        assertNotNull(caution)
        assertEquals("Mind the ladder", caution)
    }
}
