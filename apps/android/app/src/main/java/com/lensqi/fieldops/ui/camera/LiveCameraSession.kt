package com.lensqi.fieldops.ui.camera

import com.lensqi.fieldops.ui.common.TechnicianText
import com.lensqi.fieldops.data.diagnostics.DiagnosticHistory

import com.lensqi.fieldops.arcore.RegistrationView

import android.graphics.Bitmap
import android.os.SystemClock
import com.lensqi.fieldops.arcore.FloorStatus
import com.lensqi.fieldops.arcore.TargetView
import com.lensqi.fieldops.arcore.distanceAdvice
import com.lensqi.fieldops.arcore.fuseTracks
import com.lensqi.fieldops.domain.live.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.roundToInt
import retrofit2.HttpException

data class LiveCameraState(
    val objects: List<LiveObject> = emptyList(),
    val status: String = "Looking for equipment…",
    val recommendation: String = "Aim at the equipment and hold steady for detection.",
    val width: Int = 0,
    val height: Int = 0,
    val guidanceAge: Long? = null,
    val fps: Int = 0,
    /** Boxes are in view coordinates (ARCore fills the view with the camera) rather than image coordinates. */
    val viewSpace: Boolean = false,
    /** ARCore's tracking state in words, while AR runs. */
    val tracking: String? = null,
    /** Text the AI read off a screen in view, while its guidance is current. */
    val screenText: String? = null,
    val brightness: Double = 100.0,
    val sharpness: Double = 10.0,
    val motion: Double = 0.0,
    /** The part the technician confirmed, while AR runs. */
    val target: TargetView? = null,
    val floor: FloorStatus? = null,
    /** Camera frames drawn per second while AR runs; [fps] counts tracking updates, which are sampled at most 10 a second. */
    val renderFps: Int = 0,
    /** The registered equipment and the step's port, while AR runs on an inspection with a documented asset. */
    val registration: RegistrationView? = null,
)

/** What ARCore contributes to one analysed frame: where image boxes land on screen, and the parts it holds in the world. */
class SpatialFrame(
    val toView: (LiveBox) -> LiveBox?,
    val anchored: List<LiveObject>,
    val offscreen: Int,
    val tracking: String,
    val target: TargetView? = null,
    val floor: FloorStatus? = null,
    val renderFps: Int = 0,
    val registration: RegistrationView? = null,
)

/** One session owns its frames, tracker and cancellable requests. No preview frame enters the evidence queue. */
class LiveCameraSession(
    /** Accepted detections, for ARCore anchoring. Called off the main thread. */
    @Volatile var onDetection: (String, List<LiveObject>) -> Unit = { _, _ -> },
    /** The part distance coaching is about (a task step's main `lookFor`); null means the nearest measured part. */
    @Volatile var targetConcept: String? = null,
    /** The distance range the task or view request asks for, in cm; without one, distance coaching only covers focus. */
    @Volatile var framingCm: IntRange? = null,
    private val analyze: suspend (String, ByteArray, String) -> LiveResponse,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val tracker = LiveTracker()
    private val mutable = MutableStateFlow(LiveCameraState())
    val state = mutable.asStateFlow()
    @Volatile private var closed = false
    private val prefix = UUID.randomUUID().toString(); private var sequence = 0
    private var lastFrame = 0L; private var fpsAt = 0L; private var ticks = 0; private var fps = 0
    private val busy = mutableSetOf<String>(); private val due = mutableMapOf<String, Long>()
    private var guidance = ""; private var screenText: String? = null; private var guidanceFrame = ""; private var guidanceAt = 0L; private var error = ""; private var detected = false; private var scanned = false

    fun close() {
        closed = true
        scope.cancel()
        synchronized(lock) { tracker.frames.clear() }
    }

    /**
     * A new step, task or inspection. Tracks, guidance and screen text from the last one go, and so do the frames, so a
     * response still in flight for one of them matches no frame and is dropped.
     */
    fun reset() = synchronized(lock) {
        tracker.clear(); error = ""; guidance = ""; screenText = null; guidanceFrame = ""; guidanceAt = 0; detected = false; scanned = false
    }

    fun nextFrameId(): String = synchronized(lock) { "live-$prefix-${++sequence}" }

    @Volatile private var lastUpload: ByteArray? = null
    /** The most recent frame sent for detection or guidance (at most a few seconds old), for a question about the view. */
    fun latestJpeg(): ByteArray? = lastUpload

    /** True when a detection or guidance request would be sent for a frame analysed now. */
    fun uploadDue(now: Long): Boolean = synchronized(lock) { !closed && KINDS.any { it !in busy && now >= (due[it] ?: 0) } }

    // Called only by the single CameraX analyzer executor. The caller keeps ownership of the bitmap.
    fun frame(bitmap: Bitmap) {
        val now = SystemClock.elapsedRealtime()
        if (closed || now - lastFrame < 100) return
        lastFrame = now
        val ratio = 160.0 / maxOf(bitmap.width, bitmap.height)
        val small = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).roundToInt(), (bitmap.height * ratio).roundToInt(), true)
        val pixels = IntArray(small.width * small.height); small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        for (p in pixels.indices) { val c = pixels[p]; pixels[p] = (((c shr 16 and 255) * 77 + (c shr 8 and 255) * 150 + (c and 255) * 29) shr 8) }
        val gray = GrayFrame(nextFrameId(), now, small.width, small.height, pixels)
        if (small !== bitmap) small.recycle()
        analyse(gray, null, bitmap.width, bitmap.height) {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        }
    }

    /**
     * Tracks one frame, publishes feedback and, when due, uploads [encode]d JPEG bytes. Called from one thread at a time.
     * With [spatial], boxes are converted to view coordinates and fused with ARCore anchors.
     */
    fun analyse(frame: GrayFrame, spatial: SpatialFrame?, width: Int = frame.width, height: Int = frame.height, encode: () -> ByteArray) {
        val now = frame.time
        val kinds = synchronized(lock) {
            if (closed) return
            ticks++
            if (now - fpsAt >= 1000) { fps = (ticks * 1000.0 / (now - fpsAt).coerceAtLeast(1)).roundToInt(); ticks = 0; fpsAt = now }
            val motion = tracker.frames.lastOrNull()?.let { difference(it, frame) } ?: 0.0
            tracker.push(frame)
            val objects = if (spatial == null) tracker.objects
                else fuseTracks(tracker.objects.mapNotNull { o -> spatial.toView(o.box)?.let { o.copy(box = it) } }, spatial.anchored)

            if (guidance.isNotEmpty() && !tracker.current(guidanceFrame)) { guidance = ""; screenText = null; guidanceAt = 0 }
            val quality = frameQuality(frame)
            val distance = spatial?.let { distanceAdvice(objects, targetConcept, framingCm) }
            val advice = when {
                quality.brightness < 35 && guidance.isEmpty() -> "Add light so the parts and labels are visible."
                motion > 32 -> "Move more slowly so I can keep track of the connection."
                quality.sharpness < 3 && guidance.isEmpty() -> "Hold steady and bring the equipment into focus."
                guidance.isNotEmpty() -> if (distance != null) "$guidance $distance" else guidance
                distance != null -> distance
                objects.isNotEmpty() -> "Keep the connection visible. Capture a photo when ready."
                else -> "Aim at the equipment and hold steady for detection."
            }
            val lost = (detected && objects.isEmpty()) || spatial?.target?.let {
                it.state != com.lensqi.fieldops.arcore.TargetState.Tracking || it.ambiguous
            } == true
            val statusText = TechnicianText.cameraStatus(
                selectedAndTracked = spatial?.target?.let { it.state == com.lensqi.fieldops.arcore.TargetState.Tracking && !it.ambiguous } == true,
                lost = lost, paused = error.isNotBlank(), hasObjects = objects.isNotEmpty(),
            )
            mutable.value = LiveCameraState(
                objects = objects, status = statusText, recommendation = TechnicianText.cameraAdvice(advice, lost), width = width, height = height,
                guidanceAge = guidanceAt.takeIf { it > 0 }?.let { (now - it) / 1000 }, fps = fps,
                viewSpace = spatial != null, tracking = spatial?.tracking, screenText = if (lost) null else screenText,
                brightness = quality.brightness, sharpness = quality.sharpness, motion = motion,
                target = spatial?.target, floor = spatial?.floor, renderFps = spatial?.renderFps ?: 0, registration = spatial?.registration,
            )
            KINDS.filter { it !in busy && now >= (due[it] ?: 0) }.also { busy.addAll(it) }
        }
        if (kinds.isEmpty()) return
        val bytes = encode()
        lastUpload = bytes
        for (kind in kinds) scope.launch {
            val interval = if (kind == "detect") 4000L else 12000L
            synchronized(lock) { due[kind] = now + interval }
            try {
                val result = withTimeout(22000) { analyze(kind, bytes, frame.id) }
                var accepted = false
                synchronized(lock) {
                    if (!closed && result.frameId == frame.id && SystemClock.elapsedRealtime() - now < 20000) {
                        error = ""; due[kind] = SystemClock.elapsedRealtime() + maxOf(interval, result.refreshAfterMs)
                        if (kind == "detect") {
                            accepted = tracker.seed(frame.id, result.objects)
                            scanned = scanned || accepted
                            detected = detected || tracker.objects.isNotEmpty()
                        } else if (tracker.current(frame.id)) {
                            guidance = result.recommendation.orEmpty(); screenText = result.screenText; guidanceFrame = frame.id; guidanceAt = now
                        }
                    }
                }
                if (accepted) onDetection(frame.id, result.objects)
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                DiagnosticHistory.record("camera", "analysis_timeout")
                synchronized(lock) { error = "analysis_timeout"; due[kind] = SystemClock.elapsedRealtime() + 5000 }
            } catch (e: Exception) {
                DiagnosticHistory.record("camera", "analysis_unavailable", (e as? HttpException)?.code())
                val retry = (e as? HttpException)?.response()?.headers()?.get("Retry-After")?.toLongOrNull()?.times(1000) ?: 5000
                synchronized(lock) { error = if (e is HttpException && e.code() == 429) "rate_limited" else "analysis_unavailable"; due[kind] = SystemClock.elapsedRealtime() + retry.coerceAtLeast(1000) }
            } finally { synchronized(lock) { busy.remove(kind) } }
        }
    }

    private companion object { val KINDS = listOf("detect", "guide") }
}
