package com.lensqi.fieldops.ui.camera

import com.lensqi.fieldops.domain.live.LiveBox
import com.lensqi.fieldops.domain.live.LiveObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** A tap lands on the outline the technician sees, whether the camera image fills the view (AR) or is letterboxed in it (CameraX). */
class TapToAskTest {
    private val filter = LiveObject("1", "oil filter", "Oil filter", LiveBox(0.1, 0.2, 0.4, 0.6))
    private val plug = LiveObject("2", "drain plug", "Drain plug", LiveBox(0.6, 0.5, 0.9, 0.9), anchorId = "a2")
    private val parts = listOf(filter, plug)

    @Test fun `selection follows the same anchor or an unambiguous visible detection`() {
        assertNull(visibleSelection(plug, listOf(plug.copy(anchorId = "other"))))
        val moved = plug.copy(id = "3", label = "Updated label")
        assertEquals(moved, visibleSelection(plug, listOf(moved)))
        val rebound = filter.copy(id = "4")
        assertEquals(rebound, visibleSelection(filter, listOf(rebound)))
        assertNull(visibleSelection(filter, listOf(rebound, rebound.copy(id = "5"))))
    }

    @Test fun `in AR the image fills the view, so a tap is read as a fraction of the view`() {
        assertEquals(filter, tappedPart(parts, 250f, 800f, 1000f, 2000f, viewSpace = true, frameWidth = 0, frameHeight = 0))
        assertEquals(plug, tappedPart(parts, 750f, 1400f, 1000f, 2000f, viewSpace = true, frameWidth = 0, frameHeight = 0))
        assertNull(tappedPart(parts, 500f, 100f, 1000f, 2000f, viewSpace = true, frameWidth = 0, frameHeight = 0), "between the boxes: the tap hides or shows the panels instead")
    }

    @Test fun `with CameraX a 4 by 3 image is letterboxed in a tall view, and the tap is mapped through the same bars the outlines are drawn with`() {
        // A 640x480 image in a 1000x2000 view is scaled to 1000x750 and sits 625 px down.
        val top = 625f
        assertEquals(filter, tappedPart(parts, 250f, top + 0.4f * 750, 1000f, 2000f, viewSpace = false, frameWidth = 640, frameHeight = 480))
        assertEquals(plug, tappedPart(parts, 750f, top + 0.7f * 750, 1000f, 2000f, viewSpace = false, frameWidth = 640, frameHeight = 480))
        // The same view fraction that hit the filter in AR lands in the black bar here, on nothing.
        assertNull(tappedPart(parts, 250f, 300f, 1000f, 2000f, viewSpace = false, frameWidth = 640, frameHeight = 480))
    }

    @Test fun `a part without a world anchor can be tapped too, and an empty view takes no tap`() {
        assertEquals(filter, tappedPart(listOf(filter), 250f, 800f, 1000f, 2000f, viewSpace = true, frameWidth = 0, frameHeight = 0))
        assertNull(tappedPart(emptyList(), 250f, 800f, 1000f, 2000f, viewSpace = true, frameWidth = 0, frameHeight = 0))
        assertNull(tappedPart(parts, 250f, 800f, 0f, 0f, viewSpace = true, frameWidth = 0, frameHeight = 0))
    }
}
