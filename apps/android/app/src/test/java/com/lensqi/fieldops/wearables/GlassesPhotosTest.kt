package com.lensqi.fieldops.wearables

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GlassesPhotosTest {
    // A 3x2 image (w=3, h=2) and where its top-left (0,0) and top-right (2,0) pixels must land for each EXIF orientation.
    private fun corners(orientation: Int): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val fix = orientationFix(orientation)
        return fix.map(0, 0, 3, 2) to fix.map(2, 0, 3, 2)
    }

    @Test
    fun normal_and_unknown_orientations_are_left_alone() {
        assertTrue(orientationFix(1).isIdentity)
        assertTrue(orientationFix(0).isIdentity)
        assertTrue(orientationFix(99).isIdentity)
    }

    @Test
    fun each_exif_orientation_maps_pixels_upright() {
        // The stored pixel at (x, y) belongs at these upright coordinates (EXIF 2.32, Orientation tag).
        assertEquals((2 to 0) to (0 to 0), corners(2)) // mirrored horizontally
        assertEquals((2 to 1) to (0 to 1), corners(3)) // rotated 180
        assertEquals((0 to 1) to (2 to 1), corners(4)) // mirrored vertically
        assertEquals((0 to 0) to (0 to 2), corners(5)) // transposed
        assertEquals((1 to 0) to (1 to 2), corners(6)) // rotate 90 clockwise to view
        assertEquals((1 to 2) to (1 to 0), corners(7)) // transversed
        assertEquals((0 to 2) to (0 to 0), corners(8)) // rotate 270 clockwise to view
    }

    @Test
    fun quarter_turns_swap_width_and_height() {
        // Bottom-right of a 3x2 image lands inside the 2x3 result for both quarter turns.
        val (x6, y6) = orientationFix(6).map(2, 1, 3, 2)
        val (x8, y8) = orientationFix(8).map(2, 1, 3, 2)
        assertTrue(x6 in 0..1 && y6 in 0..2)
        assertTrue(x8 in 0..1 && y8 in 0..2)
    }

    @Test
    fun annex_b_nals_split_on_three_and_four_byte_start_codes() {
        val stream = byteArrayOf(0, 0, 0, 1, 0x40, 1, 2, 0, 0, 1, 0x42, 3, 0, 0, 0, 1, 0x44, 4, 5)
        val nals = annexBNals(stream)
        assertEquals(3, nals.size)
        assertArrayEquals(byteArrayOf(0x40, 1, 2), nals[0])
        assertEquals(listOf(32, 33, 34), nals.map(::hevcNalType))
    }

    @Test
    fun a_config_frame_with_a_picture_is_not_parameter_sets_only() {
        assertTrue(isParameterSetsOnly(byteArrayOf(0, 0, 0, 1, 0x40, 1, 0, 0, 0, 1, 0x42, 2, 0, 0, 0, 1, 0x44, 3, 0, 0, 0, 1, 0x4E, 5)))
        assertFalse(isParameterSetsOnly(byteArrayOf(0, 0, 0, 1, 0x40, 1, 0, 0, 0, 1, 0x26, 9))) // VPS + IDR slice
        assertFalse(isParameterSetsOnly(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun clean_annex_b_drops_a_leading_header_and_empty_nals() {
        val raw = byteArrayOf(16, 2, 26, 0, 0, 0, 1, 0, 0, 0, 1, 0x40, 1, 0, 0, 1, 0x28, 7)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x40, 1, 0, 0, 0, 1, 0x28, 7), cleanAnnexB(raw))
        assertEquals(0, cleanAnnexB(byteArrayOf(1, 2, 3)).size)
    }

    @Test
    fun parameter_sets_are_complete_only_with_vps_sps_and_pps() {
        val sets = HevcParameterSets()
        sets.offer(byteArrayOf(0, 0, 0, 1, 0x40, 1)) // VPS, sent once at stream start
        assertNull(sets.complete())
        sets.offer(byteArrayOf(0, 0, 0, 1, 0x42, 2, 0, 0, 0, 1, 0x44, 3)) // SPS + PPS
        sets.offer(byteArrayOf(0, 0, 0, 1, 0x26, 9)) // an IDR slice is not a parameter set
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x40, 1, 0, 0, 0, 1, 0x42, 2, 0, 0, 0, 1, 0x44, 3), sets.complete())
        sets.offer(byteArrayOf(0, 0, 0, 1, 0x42, 7)) // a newer SPS replaces the old one
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x40, 1, 0, 0, 0, 1, 0x42, 7, 0, 0, 0, 1, 0x44, 3), sets.complete())
        sets.reset()
        assertNull(sets.complete())
    }
}
