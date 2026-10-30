package com.lensqi.fieldops.wearables

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HevcResyncTest {
    private fun nal(type: Int) = byteArrayOf(0, 0, 0, 1, (type shl 1).toByte(), 1, 0x55)

    @Test fun `decoding restarts only at an intra random access picture`() {
        assertTrue(hasRandomAccessPicture(nal(32) + nal(33) + nal(34) + nal(19))) // VPS SPS PPS + IDR_W_RADL
        assertTrue(hasRandomAccessPicture(nal(21))) // CRA
        assertFalse(hasRandomAccessPicture(nal(1))) // trailing picture
        assertFalse(hasRandomAccessPicture(nal(32) + nal(33) + nal(34)))
    }
}
