package com.lensqi.fieldops.data.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** The phone warns before a repair photo when its clock is further from the server's than the server tolerates. */
class ClockSkewTest {
    private val now = Instant.parse("2026-11-02T18:00:00Z")

    @Test fun `the offset is the server's Date header minus the phone's clock, and an unreadable header is ignored`() {
        assertEquals(0L, ClockSkew.offsetOf("Mon, 02 Nov 2026 18:00:00 GMT", now))
        assertEquals(180_000L, ClockSkew.offsetOf("Mon, 02 Nov 2026 18:03:00 GMT", now))
        assertEquals(-90_000L, ClockSkew.offsetOf("Mon, 02 Nov 2026 17:58:30 GMT", now))
        assertNull(ClockSkew.offsetOf(null, now)); assertNull(ClockSkew.offsetOf("yesterday", now))
    }

    @Test fun `within the server's tolerance there is no warning, beyond it the direction and size are named`() {
        assertNull(ClockSkew.warning(null)); assertNull(ClockSkew.warning(0)); assertNull(ClockSkew.warning(ClockSkew.TOLERANCE_MS)); assertNull(ClockSkew.warning(-ClockSkew.TOLERANCE_MS))
        val behind = ClockSkew.warning(5 * 60_000L + 1)!!
        assertTrue(behind.contains("5 min") && behind.contains("behind"), behind)
        val ahead = ClockSkew.warning(-(ClockSkew.TOLERANCE_MS + 30_000))!!
        assertTrue(ahead.contains("2 min") && ahead.contains("ahead"), ahead)
    }
}
