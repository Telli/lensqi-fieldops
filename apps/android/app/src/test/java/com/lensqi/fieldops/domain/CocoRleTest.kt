package com.lensqi.fieldops.domain

import com.lensqi.fieldops.data.api.Recordings
import com.lensqi.fieldops.data.api.SegmentResponse
import com.lensqi.fieldops.di.NetworkModule
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class CocoRleTest {
    @Test
    fun runs_round_trip_through_the_compressed_string() {
        val rnd = Random(7)
        repeat(200) {
            val runs = IntArray(rnd.nextInt(1, 40)) { rnd.nextInt(0, 5000) }
            assertArrayEquals(runs, CocoRle.counts(CocoRle.encode(runs)))
        }
    }

    @Test
    fun decode_is_column_major_into_a_row_major_mask() {
        // 2x3 mask (h=2, w=3), column-major runs: 1 zero, 2 ones, 3 zeros → column 0 = [0,1], column 1 = [1,0].
        val mask = CocoRle.decode(CocoRle.encode(intArrayOf(1, 2, 3)), height = 2, width = 3)
        assertArrayEquals(booleanArrayOf(false, true, false, true, false, false), mask)
    }

    @Test
    fun the_recorded_sam_mask_decodes_to_exactly_its_declared_size() {
        val seg = NetworkModule.json().decodeFromString(SegmentResponse.serializer(), Recordings.text("segment")).segmentation
        for (m in seg.masks) {
            val runs = CocoRle.counts(m.rle.counts)
            assertEquals(m.rle.size[0] * m.rle.size[1], runs.sum(), "runs cover the mask exactly")
            val ones = CocoRle.decode(m.rle.counts, m.rle.size[0], m.rle.size[1]).count { it }
            assertTrue(ones > 0 && ones < runs.sum())
        }
    }
}
