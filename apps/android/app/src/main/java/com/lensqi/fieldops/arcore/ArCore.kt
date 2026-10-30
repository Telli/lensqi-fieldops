package com.lensqi.fieldops.arcore

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.lensqi.fieldops.wearables.yuvPixel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ArAvailability { Checking, Ready, NeedsInstall, Unsupported }

/** Google Play Services for AR: availability, install, and a session configured for equipment inspection. */
object ArCore {
    fun availability(context: Context): ArAvailability = runCatching {
        val a = ArCoreApk.getInstance().checkAvailability(context)
        when {
            a == ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArAvailability.Ready
            a == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED || a == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> ArAvailability.NeedsInstall
            a.isTransient -> ArAvailability.Checking
            else -> ArAvailability.Unsupported
        }
    }.getOrDefault(ArAvailability.Unsupported)

    /** Starts the Play Store install of Google Play Services for AR; true when it is already installed. */
    fun requestInstall(activity: Activity): Boolean = runCatching {
        ArCoreApk.getInstance().requestInstall(activity, true) == ArCoreApk.InstallStatus.INSTALLED
    }.getOrDefault(false)

    /** A session with motion tracking, vertical and horizontal planes, and the Depth API when the device has it. */
    fun openSession(context: Context): Pair<Session, Boolean> {
        val session = Session(context)
        val depth = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        session.configure(session.config.apply {
            focusMode = Config.FocusMode.AUTO
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            depthMode = if (depth) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        })
        return session to depth
    }
}

class ArCoreAnchor(private val anchor: Anchor) : WorldAnchor {
    override fun position(): DoubleArray? = anchor.takeIf { it.trackingState == TrackingState.TRACKING }?.pose
        ?.let { doubleArrayOf(it.tx().toDouble(), it.ty().toDouble(), it.tz().toDouble()) }
    override val stopped get() = anchor.trackingState == TrackingState.STOPPED
    override fun detach() = anchor.detach()
}

fun Session.anchorAt(p: DoubleArray): WorldAnchor? = runCatching {
    ArCoreAnchor(createAnchor(Pose(floatArrayOf(p[0].toFloat(), p[1].toFloat(), p[2].toFloat()), floatArrayOf(0f, 0f, 0f, 1f))))
}.getOrNull()

/** Clockwise rotation (0, 90, 180, 270) that makes the CPU camera image upright in the view. */
fun Frame.imageRotation(): Int {
    val out = FloatArray(4)
    transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, floatArrayOf(0f, 0f, 1f, 0f), Coordinates2d.VIEW_NORMALIZED, out)
    val dx = out[2] - out[0]; val dy = out[3] - out[1]
    return if (abs(dx) >= abs(dy)) (if (dx > 0) 0 else 180) else (if (dy > 0) 90 else 270)
}

fun Frame.viewMap(rotation: Int): ViewMap {
    val points = listOf(0.0 to 0.0, 1.0 to 0.0, 0.0 to 1.0)
        .flatMap { (u, v) -> uprightToSensor(u, v, rotation).toList() }.map { it.toFloat() }.toFloatArray()
    val o = FloatArray(6)
    transformCoordinates2d(Coordinates2d.IMAGE_NORMALIZED, points, Coordinates2d.VIEW_NORMALIZED, o)
    return ViewMap(o[0].toDouble(), o[1].toDouble(), (o[2] - o[0]).toDouble(), (o[3] - o[1]).toDouble(), (o[4] - o[0]).toDouble(), (o[5] - o[1]).toDouble())
}

/** The camera for this frame, or null while ARCore is not tracking. */
fun Frame.cameraView(map: ViewMap): ArCameraView? {
    val c = camera
    if (c.trackingState != TrackingState.TRACKING) return null
    val m = FloatArray(16); c.displayOrientedPose.toMatrix(m, 0)
    val p = FloatArray(16); c.getProjectionMatrix(p, 0, 0.05f, 20f)
    val t = c.pose.translation
    return ArCameraView(m, p, map, doubleArrayOf(t[0].toDouble(), t[1].toDouble(), t[2].toDouble()))
}

/** What the technician sees about ARCore's state; tracking failures name the fix. */
fun Frame.trackingLabel(depth: Boolean): String = when (camera.trackingState) {
    TrackingState.TRACKING -> if (depth) "Live AR · ARCore tracking + depth" else "Live AR · ARCore tracking"
    else -> when (camera.trackingFailureReason) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "AR paused · add light"
        TrackingFailureReason.EXCESSIVE_MOTION -> "AR paused · move more slowly"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "AR paused · aim at textured surfaces"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "AR paused · camera busy"
        else -> "AR starting · move the phone slowly"
    }
}

/**
 * Depth measured at this frame, in upright-image coordinates: the Depth API image when available, otherwise hit tests
 * against the feature points and planes ARCore is tracking.
 */
fun Frame.depthSamples(view: ArCameraView, rotation: Int, viewWidth: Int, viewHeight: Int, depthApi: Boolean): DepthSamples {
    if (depthApi) runCatching {
        acquireDepthImage16Bits().use { image ->
            val plane = image.planes[0]
            val buffer = plane.buffer.order(ByteOrder.nativeOrder())
            val points = mutableListOf<DepthSample>()
            for (gy in 0 until 36) for (gx in 0 until 48) {
                val u = (gx + .5) / 48; val v = (gy + .5) / 36
                val (s, t) = uprightToSensor(u, v, rotation)
                val x = (s * image.width).toInt().coerceIn(0, image.width - 1); val y = (t * image.height).toInt().coerceIn(0, image.height - 1)
                val mm = buffer.getShort(y * plane.rowStride + x * plane.pixelStride).toInt() and 0xFFFF
                if (mm in 100..8000) points += DepthSample(u, v, mm / 1000.0)
            }
            if (points.size >= 20) return DepthSamples(DepthSource.DepthApi, points)
        }
    }
    val points = mutableListOf<DepthSample>()
    if (viewWidth > 0 && viewHeight > 0) for (gy in 0 until 9) for (gx in 0 until 12) {
        val u = (gx + .5) / 12; val v = (gy + .5) / 9
        val (vx, vy) = view.imageToView.point(u, v)
        if (vx !in 0.0..1.0 || vy !in 0.0..1.0) continue
        val hit = hitTest((vx * viewWidth).toFloat(), (vy * viewHeight).toFloat()).firstOrNull { h ->
            when (val t = h.trackable) {
                is Plane -> t.isPoseInPolygon(h.hitPose)
                is Point, is DepthPoint -> true
                else -> false
            }
        } ?: continue
        val w = hit.hitPose.translation
        val forward = -view.toCamera(doubleArrayOf(w[0].toDouble(), w[1].toDouble(), w[2].toDouble()))[2]
        if (forward in 0.1..8.0) points += DepthSample(u, v, forward)
    }
    return DepthSamples(DepthSource.HitTest, points)
}

/** A copy of a camera image's YUV planes, so the image goes back to ARCore before any conversion work. */
class YuvCopy(val width: Int, val height: Int, private val planes: List<Triple<ByteArray, Int, Int>>) {
    private fun at(plane: Int, x: Int, y: Int): Int { val (b, row, px) = planes[plane]; return b[y * row + x * px].toInt() and 255 }

    /** Upright pixels no larger than [maxSide]: luma only, or ARGB. */
    fun upright(rotation: Int, maxSide: Int, color: Boolean): Triple<IntArray, Int, Int> {
        val (uw, uh) = if (rotation % 180 == 0) width to height else height to width
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(uw, uh))
        val w = (uw * scale).roundToInt(); val h = (uh * scale).roundToInt()
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val (s, t) = uprightToSensor((x + .5) / w, (y + .5) / h, rotation)
            val sx = (s * width).toInt().coerceIn(0, width - 1); val sy = (t * height).toInt().coerceIn(0, height - 1)
            out[y * w + x] = if (color) yuvPixel(at(0, sx, sy), at(1, sx / 2, sy / 2), at(2, sx / 2, sy / 2), fullRange = true) else at(0, sx, sy)
        }
        return Triple(out, w, h)
    }

    fun uprightBitmap(rotation: Int, maxSide: Int): Bitmap = upright(rotation, maxSide, true).let { (p, w, h) -> Bitmap.createBitmap(p, w, h, Bitmap.Config.ARGB_8888) }
}

fun Image.copyYuv(): YuvCopy = YuvCopy(width, height, planes.map { p ->
    val b: ByteBuffer = p.buffer.duplicate().apply { rewind() }
    Triple(ByteArray(b.remaining()).also { b.get(it) }, p.rowStride, p.pixelStride)
})
