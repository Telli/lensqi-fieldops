package com.lensqi.fieldops.task

import com.lensqi.fieldops.data.settings.PersonProfile
import com.lensqi.fieldops.data.settings.parseHeightCm
import com.lensqi.fieldops.domain.live.LiveBox
import com.lensqi.fieldops.domain.live.LiveObject
import com.lensqi.fieldops.ui.task.measuredJson
import com.lensqi.fieldops.ui.task.reachAdvice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Heights as people say them, and whether an access step is needed: measured height against estimated reach. */
class ReachTest {
    @Test fun `heights are read the way people say them`() {
        assertEquals(188, parseHeightCm("6'2\""))
        assertEquals(188, parseHeightCm("6' 2"))
        assertEquals(188, parseHeightCm("6 ft 2 in"))
        assertEquals(188, parseHeightCm("6ft2"))
        assertEquals(188, parseHeightCm("74 in"))
        assertEquals(188, parseHeightCm("188"))
        assertEquals(188, parseHeightCm("188 cm"))
        assertEquals(188, parseHeightCm("1.88 m"))
        assertEquals(188, parseHeightCm("1.88"))
        assertEquals(183, parseHeightCm("6’"))
        assertNull(parseHeightCm("tall"))
        assertNull(parseHeightCm("30"))
        assertEquals(250, PersonProfile(188).standingReachCm)
    }

    private fun detector(heightCm: Int?) = LiveObject("a", "smoke detector", "smoke detector", LiveBox(0.4, 0.4, 0.6, 0.6), 0.9,
        distanceCm = 120, spatialAnchored = true, heightAboveFloorCm = heightCm)

    @Test fun `a part within reach offers to skip the access step, one above it does not`() {
        val within = reachAdvice("smoke detector", listOf(detector(238)), reachCm = 250)!!
        assertTrue(within.canSkip)
        assertEquals(238 to 250, within.partHeightCm to within.reachCm)
        assertTrue(within.text.contains("2.4 m up (measured)") && within.text.contains("estimated"))
        assertFalse(reachAdvice("smoke detector", listOf(detector(245)), reachCm = 250)!!.canSkip)   // inside the 10 cm margin: keep the stool
        assertFalse(reachAdvice("smoke detector", listOf(detector(238)), reachCm = null)!!.canSkip)
        assertTrue(reachAdvice("smoke detector", listOf(detector(null)), reachCm = 250)!!.text.contains("point at the floor"))
        assertNull(reachAdvice(null, listOf(detector(238)), reachCm = 250))
    }

    @Test fun `measured parts go to the server as plain numbers`() {
        assertEquals("""[{"part":"smoke detector","distanceCm":120,"heightAboveFloorCm":238}]""", measuredJson(listOf(detector(238), detector(240).copy(id = "b"))))
        assertNull(measuredJson(listOf(detector(238).copy(spatialAnchored = false))))
    }
}
