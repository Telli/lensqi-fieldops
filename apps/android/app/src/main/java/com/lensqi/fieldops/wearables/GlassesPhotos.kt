package com.lensqi.fieldops.wearables

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.meta.wearable.dat.camera.types.PhotoData
import java.io.ByteArrayInputStream
import java.io.File

/** Mirror horizontally first (if [mirror]), then rotate clockwise by [degrees]. */
data class OrientationFix(val degrees: Int, val mirror: Boolean) {
    val isIdentity get() = degrees == 0 && !mirror
}

/** EXIF `Orientation` (1..8) to the transform that makes the pixels upright. Unknown values are left alone. */
fun orientationFix(exifOrientation: Int): OrientationFix = when (exifOrientation) {
    2 -> OrientationFix(0, mirror = true) // flip horizontal
    3 -> OrientationFix(180, mirror = false)
    4 -> OrientationFix(180, mirror = true) // flip vertical
    5 -> OrientationFix(270, mirror = true) // transpose
    6 -> OrientationFix(90, mirror = false)
    7 -> OrientationFix(90, mirror = true) // transverse
    8 -> OrientationFix(270, mirror = false)
    else -> OrientationFix(0, mirror = false)
}

/** Where pixel (x, y) of a [width] x [height] image lands after [fix]; used to pin the transform down in tests. */
fun OrientationFix.map(x: Int, y: Int, width: Int, height: Int): Pair<Int, Int> {
    var px = if (mirror) width - 1 - x else x
    var py = y
    var w = width
    var h = height
    repeat((degrees / 90) % 4) { // one clockwise quarter turn: (x, y) -> (h - 1 - y, x)
        val nx = h - 1 - py
        py = px; px = nx
        val t = w; w = h; h = t
    }
    return px to py
}

/**
 * Turns what `Stream.capturePhoto()` returns into an upright full-resolution JPEG in [dir]. The glasses deliver HEIC
 * (orientation in EXIF, which BitmapFactory does not apply) or a Bitmap; the API accepts JPEG or PNG evidence, so the
 * photo is re-encoded once at quality 95 with no scaling. This JPEG is the original the queue hashes and uploads.
 */
object GlassesPhotos {
    const val JPEG_QUALITY = 95

    fun writeJpeg(photo: PhotoData, dir: File, name: String = "glasses-${System.currentTimeMillis()}.jpg"): File {
        val bitmap = when (photo) {
            is PhotoData.Bitmap -> photo.bitmap
            is PhotoData.HEIC -> decodeUpright(photo.data.duplicate().apply { rewind() }.let { b -> ByteArray(b.remaining()).also { b.get(it) } })
        }
        val file = File(dir, name)
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) { "JPEG encoding failed" } }
        return file
    }

    fun decodeUpright(bytes: ByteArray): Bitmap {
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("The glasses photo could not be decoded")
        val orientation = runCatching {
            ByteArrayInputStream(bytes).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val fix = orientationFix(orientation)
        if (fix.isIdentity) return decoded
        val matrix = Matrix().apply {
            if (fix.mirror) postScale(-1f, 1f)
            if (fix.degrees != 0) postRotate(fix.degrees.toFloat())
        }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
    }
}
