package com.lensqi.fieldops.wearables

import java.nio.ByteBuffer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LiveGlassesTest {
    @Test fun `guidance must settle and repeated words stay silent`() {
        val gate = SpokenFeedbackGate()
        assertNull(gate.next("Hold steady", 0))
        assertNull(gate.next("Hold steady", 1499))
        assertEquals("Hold steady", gate.next("Hold steady", 1500))
        assertNull(gate.next("Hold steady", 120000))
    }
    @Test fun `rapidly changing feedback cannot interrupt the wearer`() {
        val gate = SpokenFeedbackGate()
        assertNull(gate.next("Add light", 0))
        assertNull(gate.next("Hold steady", 800))
        assertNull(gate.next("Add light", 1600))
        assertNull(gate.next("Add light", 3000))
        assertEquals("Add light", gate.next("Add light", 3100))
    }
    @Test fun `fresh advice is spaced out and empty feedback is never spoken`() {
        val gate = SpokenFeedbackGate()
        gate.next("First", 0); assertEquals("First", gate.next("First", 1500))
        gate.next("Second", 2000); assertNull(gate.next("Second", 4000))
        assertEquals("Second", gate.next("Second", 9500))
        assertNull(gate.next("", 20000)); assertNull(gate.next("", 22000))
    }
    private val step = LiveSession("task-1", LiveSessionKind.Task, "drain plug", "Step 2")
    @Test fun `live assist resumes only once the shutter photo is taken and the camera streams again`() {
        // A photo paused it for this very session: not while capturing, not while the service still runs, not without a stream.
        assertFalse(shouldResumeLive(streaming = true, liveEnabled = false, capturing = true, pending = step, session = step))
        assertFalse(shouldResumeLive(streaming = true, liveEnabled = true, capturing = false, pending = step, session = step))
        assertFalse(shouldResumeLive(streaming = false, liveEnabled = false, capturing = false, pending = step, session = step))
        assertTrue(shouldResumeLive(streaming = true, liveEnabled = false, capturing = false, pending = step, session = step))
    }
    @Test fun `a pause for another inspection or step never restarts live assist here`() {
        assertFalse(shouldResumeLive(streaming = true, liveEnabled = false, capturing = false, pending = null, session = step))
        assertFalse(shouldResumeLive(streaming = true, liveEnabled = false, capturing = false, pending = step.copy(targetConcept = "oil filter"), session = step))
        assertFalse(shouldResumeLive(streaming = true, liveEnabled = false, capturing = false, pending = step.copy(id = "task-2"), session = step))
        // A different label is the same work.
        assertTrue(shouldResumeLive(streaming = true, liveEnabled = false, capturing = false, pending = step.copy(label = "Step 2 · Loosen"), session = step))
    }
    @Test fun `decoder plane addressing respects offsets padding and interleaved chroma`() {
        val bytes = ByteBuffer.wrap(byteArrayOf(99, 99, 10, 70, 20, 80, 0, 0, 30, 90, 40, 100))
        bytes.position(2)
        val plane = YuvPlane(bytes, rowStride = 6, pixelStride = 2)
        assertEquals(10, plane.at(0, 0)); assertEquals(20, plane.at(1, 0))
        assertEquals(30, plane.at(0, 1)); assertEquals(40, plane.at(1, 1))
        assertEquals(2, bytes.position())
    }
    @Test fun `limited and full range decode black and white without tinted channels`() {
        assertEquals(0xff000000.toInt(), yuvPixel(16, 128, 128))
        assertEquals(0xffffffff.toInt(), yuvPixel(235, 128, 128))
        assertEquals(0xff000000.toInt(), yuvPixel(0, 128, 128, fullRange = true))
        assertEquals(0xffffffff.toInt(), yuvPixel(255, 128, 128, fullRange = true))
    }
    @Test fun `UV planes are not reversed for a red test patch`() {
        val red = yuvPixel(81, 90, 240)
        assertTrue((red shr 16 and 255) > 245)
        assertTrue((red shr 8 and 255) < 10)
        assertTrue((red and 255) < 10)
    }
}
