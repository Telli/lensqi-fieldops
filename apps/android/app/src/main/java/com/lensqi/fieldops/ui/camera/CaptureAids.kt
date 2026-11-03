package com.lensqi.fieldops.ui.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.lensqi.fieldops.arcore.TargetState
import com.lensqi.fieldops.arcore.TargetView
import com.lensqi.fieldops.wearables.orientationFix
import java.io.ByteArrayInputStream

/**
 * The part to ask about on the still after the shutter: the part the server will compare ([capturePart]), else the
 * confirmed target if it was tracked when the shutter was pressed. Null asks nothing, so a wide view or a photo of
 * something else goes straight on. The live outline is not carried over: the question is about the photo itself.
 */
fun partToConfirm(capturePart: String?, target: TargetView?): String? =
    capturePart?.takeIf { it.isNotBlank() } ?: target?.takeIf { it.state == TargetState.Tracking && !it.ambiguous }?.label

/** Wording for the framing aid. Lining the view up helps the comparison; it never proves the repair. */
const val FRAMING_AID_NOTE = "Framing aid only · lining up with the before photo helps the comparison, it doesn't prove the repair"

/** Pixels decoded for display, with the size of the stored image they came from: server geometry (outlines, masks) is in those source pixels. */
data class DecodedPhoto(val bitmap: Bitmap, val sourceWidth: Int, val sourceHeight: Int)

/** The power-of-two subsampling that keeps the long side under about [maxSide] pixels; a full-size decode of a phone photo is tens of MB. */
fun sampleFor(width: Int, height: Int, maxSide: Int): Int {
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
    return sample
}

/** A photo decoded as stored (no orientation change) and at most about [maxSide] pixels on its long side; null when the bytes are not an image. */
fun decodeBounded(bytes: ByteArray, maxSide: Int): DecodedPhoto? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val sample = sampleFor(bounds.outWidth, bounds.outHeight, maxSide)
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return DecodedPhoto(decoded, bounds.outWidth, bounds.outHeight)
}

/** A photo decoded upright (EXIF orientation applied) and at most about [maxSide] pixels on its long side, for display only. */
fun uprightBitmap(bytes: ByteArray, maxSide: Int): Bitmap? {
    val decoded = decodeBounded(bytes, maxSide)?.bitmap ?: return null
    val fix = orientationFix(runCatching {
        ByteArrayInputStream(bytes).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL))
    if (fix.isIdentity) return decoded
    val matrix = Matrix().apply { if (fix.mirror) postScale(-1f, 1f); postRotate(fix.degrees.toFloat()) }
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
}

/**
 * The parts ARCore measured, as last seen on the live view, for the photo taken next. Live assist stops at the shutter
 * and the live objects empty before the photo is saved, so the note is the last non-empty measurement, and only while it
 * is fresh: a measurement older than [maxAgeMs] is not pinned to a photo of what may be another view.
 */
class MeasuredNote(private val maxAgeMs: Long = 10_000, private val clock: () -> Long = android.os.SystemClock::elapsedRealtime) {
    private var json: String? = null
    private var at = 0L
    private var frozen: String? = null
    private var hasFrozen = false
    /** A new measurement replaces the last; an empty frame leaves the last one in place. */
    fun seen(measuredJson: String?) { if (measuredJson != null) { json = measuredJson; at = clock() } }
    /** What is fresh now. */
    fun recent(): String? = json?.takeIf { clock() - at <= maxAgeMs }
    /** The shutter: the photo's note is what was fresh at this moment, however long its confirmation takes afterwards. */
    fun freeze() { frozen = recent(); hasFrozen = true }
    /** The note for the photo just taken: the one frozen at the shutter, else what is fresh now. Used once per photo. */
    fun take(): String? {
        val note = if (hasFrozen) frozen else recent()
        frozen = null; hasFrozen = false
        return note
    }
}
