package com.lensqi.fieldops.domain

/**
 * COCO compressed RLE as emitted by meta-sam-parser: size [height, width], counts alternate 0-runs and 1-runs in
 * column-major order, each run LEB128-like in 6-bit characters offset by 48, delta-coded against the run two back.
 * Reimplemented from the format (the browser UI's decoder in wwwroot/app.js is the behavioural reference).
 */
object CocoRle {
    fun counts(encoded: String): IntArray {
        val out = ArrayList<Int>()
        var p = 0
        while (p < encoded.length) {
            var x = 0L
            var k = 0
            var more = true
            while (more) {
                val c = encoded[p].code - 48
                x = x or ((c and 0x1f).toLong() shl (5 * k))
                more = (c and 0x20) != 0
                p++
                k++
                if (!more && (c and 0x10) != 0) x = x or (-1L shl (5 * k))
            }
            if (out.size > 2) x += out[out.size - 2]
            out.add(x.toInt())
        }
        return out.toIntArray()
    }

    /** Row-major mask of height*width: true where the object is. */
    fun decode(encoded: String, height: Int, width: Int): BooleanArray {
        val columnMajor = BooleanArray(height * width)
        var pos = 0
        var value = false
        for (run in counts(encoded)) {
            val end = minOf(pos + run, columnMajor.size)
            if (value) columnMajor.fill(true, pos, end)
            pos = end
            value = !value
        }
        return BooleanArray(height * width) { i -> columnMajor[(i % width) * height + i / width] }
    }

    /** Inverse of [counts], used by tests to round-trip masks. */
    fun encode(runs: IntArray): String = buildString {
        for (i in runs.indices) {
            var x = runs[i].toLong()
            if (i > 2) x -= runs[i - 2]
            var more = true
            while (more) {
                var c = (x and 0x1f).toInt()
                x = x shr 5
                more = if ((c and 0x10) != 0) x != -1L else x != 0L
                if (more) c = c or 0x20
                append((c + 48).toChar())
            }
        }
    }
}
