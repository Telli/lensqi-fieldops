package com.lensqi.fieldops.wearables

import android.graphics.Bitmap
import android.media.Image
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Plane addressing is explicit: decoder output can be planar or interleaved, with padding and a crop. */
data class YuvPlane(val bytes: ByteBuffer, val rowStride: Int, val pixelStride: Int) {
    fun at(x: Int, y: Int): Int = bytes.get(bytes.position() + y * rowStride + x * pixelStride).toInt() and 255
}

fun yuvPixel(y: Int, u: Int, v: Int, fullRange: Boolean = false, bt709: Boolean = false): Int {
    val light = if (fullRange) y.toDouble() else (y - 16) * 1.164
    val cb = u - 128; val cr = v - 128
    val r = light + cr * (if (bt709) 1.793 else 1.596) * (if (fullRange) .878 else 1.0)
    val g = light - (cb * (if (bt709) .213 else .392) + cr * (if (bt709) .533 else .813)) * (if (fullRange) .878 else 1.0)
    val b = light + cb * (if (bt709) 2.112 else 2.017) * (if (fullRange) .878 else 1.0)
    return (255 shl 24) or (r.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)
}

/** Upright decoded stream pixels, reduced before RGB allocation. No preview Surface or screen capture is needed. */
fun Image.analysisBitmap(fullRange: Boolean, bt709: Boolean): Bitmap {
    val crop = cropRect
    val scale = minOf(1.0, 640.0 / maxOf(crop.width(), crop.height()))
    val w = (crop.width() * scale).roundToInt(); val h = (crop.height() * scale).roundToInt()
    val planes = planes.map { YuvPlane(it.buffer.duplicate(), it.rowStride, it.pixelStride) }
    require(planes.size == 3) { "Decoder did not return YUV420 planes" }
    val pixels = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val sx = crop.left + (x / scale).toInt(); val sy = crop.top + (y / scale).toInt()
        pixels[y * w + x] = yuvPixel(planes[0].at(sx, sy), planes[1].at(sx / 2, sy / 2), planes[2].at(sx / 2, sy / 2), fullRange, bt709)
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}
