package com.lensqi.fieldops.ui.camera

import android.graphics.Bitmap
import android.os.SystemClock
import com.lensqi.fieldops.domain.live.LiveObject
import com.lensqi.fieldops.domain.live.PointingSnapshot
import com.lensqi.fieldops.domain.live.PointingTracker
import com.lensqi.fieldops.domain.live.PointingView
import com.lensqi.fieldops.domain.live.pointingIntervalMs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the camera screen draws: the pointing, and how to map it onto the picture. */
data class PointingScreen(
    val view: PointingView = PointingView(),
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    /** The tip is in view coordinates (ARCore fills the view) rather than letterboxed image coordinates. */
    val viewSpace: Boolean = false,
    val unavailable: Boolean = false,
)

/** The camera's frames through the hand detector and the tracker. Uploads nothing: a frame kept here leaves only with a question. */
class PointingSession(private val detector: HandDetector) {
    private val tracker = PointingTracker()
    // Already off from the start means no frame will ever say so.
    private val mutable = MutableStateFlow(PointingScreen(unavailable = detector.failed))
    val state = mutable.asStateFlow()
    @Volatile private var closed = false
    @Volatile private var lastFrame = -1L
    @Volatile private var lastHand = -1L
    private var ticks = 0; private var ticksAt = 0L
    /** For the diagnostics line: what one detection took, and detections a second. */
    @Volatile var inferenceMs = 0L; private set
    @Volatile var rate = 0; private set

    /** True when the frame being analysed should be looked at. */
    fun due(now: Long): Boolean = !closed && !detector.failed && (lastFrame < 0 || now - lastFrame >= pointingIntervalMs(lastHand, now))

    /**
     * One frame, on the thread analysing the camera. [bitmap] is upright; the caller keeps it. [parts] and [toParts] share one
     * coordinate space, the view in AR and the frame otherwise. [encode] is only called when a frame is to be kept for a question.
     */
    fun frame(bitmap: Bitmap, now: Long, parts: List<LiveObject>, viewSpace: Boolean, toParts: (Double, Double) -> Pair<Double, Double>, encode: () -> ByteArray?) {
        if (closed) return
        lastFrame = now
        val started = SystemClock.elapsedRealtime()
        val hands = detector.detect(bitmap, now)
        inferenceMs = SystemClock.elapsedRealtime() - started
        ticks++
        if (now - ticksAt >= 1000) { rate = (ticks * 1000 / (now - ticksAt).coerceAtLeast(1)).toInt(); ticks = 0; ticksAt = now }
        if (hands.isNotEmpty()) lastHand = now
        val view = tracker.update(now, hands, bitmap.width.toDouble() / bitmap.height, parts, toParts)
        if (tracker.wantsSnapshot(now)) tracker.storeSnapshot(runCatching { encode() }.getOrNull(), now)
        mutable.value = PointingScreen(view, bitmap.width, bitmap.height, viewSpace, detector.failed)
    }

    /** The pointing for a question asked now, once. */
    fun take(now: Long): PointingSnapshot? = tracker.take(now).also { mutable.value = mutable.value.copy(view = PointingView()) }

    /** A new step, task or inspection. */
    fun reset() { tracker.reset(); mutable.value = mutable.value.copy(view = PointingView()) }

    fun close() { closed = true; detector.close(); reset() }
}
