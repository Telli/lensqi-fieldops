package com.lensqi.fieldops.domain.live

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LiveTrackerTest {
    private fun frame(id: Int, dx: Int = 0, dy: Int = 0, blank: Boolean = false): GrayFrame {
        val pixels = IntArray(160 * 120) { 24 }
        if (!blank) for (y in 25 until 85) for (x in 35 until 105) pixels[(y + dy) * 160 + x + dx] = ((x * 719 + y * 173 + x * y * 31) % 200) + 40
        return GrayFrame("f-$id", id * 100L, 160, 120, pixels)
    }
    private val part = LiveObject("cable", "cable", "cable", LiveBox(35.0 / 160, 25.0 / 120, 105.0 / 160, 85.0 / 120))
    @Test fun `box follows actual translated pixels`() {
        val b = trackBox(frame(1), frame(2, 4, -3), part.box)!!
        assertEquals(4.0 / 160, b.left - part.box.left, .001); assertEquals(-3.0 / 120, b.top - part.box.top, .001)
    }
    @Test fun `occlusion removes stale overlay`() { assertNull(trackBox(frame(1), frame(2, blank = true), part.box)) }
    @Test fun `delayed detection rebases to current frame`() {
        val t = LiveTracker(); t.push(frame(1)); t.push(frame(2, 2)); t.push(frame(3, 4)); assertTrue(t.seed("f-1", listOf(part)))
        assertEquals(part.box.left + 4.0 / 160, t.objects.single().box.left, .001)
    }
    @Test fun `unknown frames and gaps cannot retain tracks`() {
        val t = LiveTracker(); t.push(frame(1)); assertFalse(t.seed("foreign", listOf(part))); t.seed("f-1", listOf(part)); t.push(frame(20)); assertTrue(t.objects.isEmpty())
    }
    @Test fun `no detections means no objects and old tracks expire`() {
        val t = LiveTracker(); t.push(frame(1)); assertTrue(t.objects.isEmpty()); t.seed("f-1", listOf(part))
        for (n in 2..205) t.push(frame(n)); assertTrue(t.objects.isEmpty())
    }
    @Test fun `a cleared tracker drops a detection for a frame from before`() {
        val t = LiveTracker(); t.push(frame(1)); t.seed("f-1", listOf(part)); t.clear()
        assertTrue(t.objects.isEmpty()); t.push(frame(2)); assertFalse(t.seed("f-1", listOf(part))); assertFalse(t.current("f-1"))
    }
    @Test fun `guidance expires on scene change and darkness is measured`() {
        val t = LiveTracker(); t.push(frame(1)); t.push(frame(2, blank = true)); assertFalse(t.current("f-1")); assertEquals(24.0, frameQuality(frame(1, blank = true)).brightness)
    }
    @Test fun `an anchor-only box fades with its age, from 0point6 at two seconds to 0point25 at ten, and a live box does not`() {
        assertEquals(1f, staleAlpha(null))
        assertEquals(0.6f, staleAlpha(0), 1e-6f); assertEquals(0.6f, staleAlpha(2), 1e-6f)
        assertEquals(0.425f, staleAlpha(6), 1e-6f, "halfway between two and ten seconds is halfway between the alphas")
        assertEquals(0.25f, staleAlpha(10), 1e-6f); assertEquals(0.25f, staleAlpha(45), 1e-6f)
        // Monotonic: an older box is never drawn stronger than a newer one.
        (0..12).zipWithNext().forEach { (a, b) -> assertTrue(staleAlpha(b) <= staleAlpha(a), "$b s drawn stronger than $a s") }
    }
}
