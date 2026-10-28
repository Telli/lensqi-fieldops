package com.lensqi.fieldops.ui.camera

import android.graphics.Bitmap
import android.os.SystemClock
import com.lensqi.fieldops.arcore.Pose6DoF
import com.lensqi.fieldops.arcore.PoseSnapshot
import com.lensqi.fieldops.arcore.SpatialAnchorTracker
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
    val spatialMode: Boolean = false,
    val activeAnchors: Int = 0,
    val brightness: Double = 100.0,
    val sharpness: Double = 10.0,
    val motion: Double = 0.0,
)

/** One session owns its frames, 2D tracker, optional 3D spatial anchor tracker, and cancellable requests. No preview frame enters the evidence queue. */
class LiveCameraSession(
    @Volatile var spatialMode: Boolean = false,
    private val analyze: suspend (String, ByteArray, String) -> LiveResponse,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val tracker = LiveTracker()
    private val spatialTracker = SpatialAnchorTracker()
    private val mutable = MutableStateFlow(LiveCameraState(spatialMode = spatialMode))
    val state = mutable.asStateFlow()
    @Volatile private var closed = false
    private val prefix = UUID.randomUUID().toString(); private var sequence = 0
    private var lastFrame = 0L; private var fpsAt = 0L; private var ticks = 0; private var fps = 0
    private val busy = mutableSetOf<String>(); private val due = mutableMapOf<String, Long>()
    private var guidance = ""; private var guidanceFrame = ""; private var guidanceAt = 0L; private var error = ""; private var detected = false; private var scanned = false
    private var currentPose = Pose6DoF()
    private var latestDepthMeters = 0.34

    fun close() {
        closed = true
        scope.cancel()
        synchronized(lock) {
            tracker.frames.clear()
            spatialTracker.clear()
        }
    }

    /** Updates 6DoF camera orientation/translation from ARCore or device rotation vector sensor. */
    fun updatePose(pose: Pose6DoF, depthMeters: Double? = null) {
        synchronized(lock) {
            currentPose = pose
            if (depthMeters != null && depthMeters in 0.10..5.0) latestDepthMeters = depthMeters
        }
    }

    // Called only by the single CameraX analyzer executor. The caller keeps ownership of the bitmap.
    fun frame(bitmap: Bitmap) {
        val now = SystemClock.elapsedRealtime()
        if (closed || now - lastFrame < 100) return
        lastFrame = now; ticks++
        if (now - fpsAt >= 1000) { fps = (ticks * 1000.0 / (now - fpsAt).coerceAtLeast(1)).roundToInt(); ticks = 0; fpsAt = now }
        val ratio = 160.0 / maxOf(bitmap.width, bitmap.height)
        val small = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).roundToInt(), (bitmap.height * ratio).roundToInt(), true)
        val pixels = IntArray(small.width * small.height); small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        for (p in pixels.indices) { val c = pixels[p]; pixels[p] = (((c shr 16 and 255) * 77 + (c shr 8 and 255) * 150 + (c and 255) * 29) shr 8) }
        val frame = GrayFrame("live-$prefix-${++sequence}", now, small.width, small.height, pixels)
        if (small !== bitmap) small.recycle()
        val kinds = synchronized(lock) {
            if (closed) return
            val motion = tracker.frames.lastOrNull()?.let { difference(it, frame) } ?: 0.0
            tracker.push(frame)
            val spatialObjects = if (spatialMode) {
                spatialTracker.pushPose(PoseSnapshot(frame.id, now, currentPose, defaultDepthMeters = latestDepthMeters))
            } else emptyList()
            val mergedObjects = if (spatialMode && spatialObjects.isNotEmpty()) {
                // Fuse 2D patch refinement when visible with 3D world-locked anchor distance & persistence.
                val patchByConcept = tracker.objects.associateBy { it.concept.lowercase() }
                spatialObjects.map { sp ->
                    val patch = patchByConcept[sp.concept.lowercase()]
                    if (patch != null) patch.copy(distanceCm = sp.distanceCm, spatialAnchored = true) else sp
                }
            } else tracker.objects

            if (guidance.isNotEmpty() && !tracker.current(guidanceFrame)) { guidance = ""; guidanceAt = 0 }
            val quality = frameQuality(frame)
            val metricAdvice = if (spatialMode) SpatialAnchorTracker.metricDistanceGuidance(mergedObjects) else null
            val advice = when {
                quality.brightness < 35 && guidance.isEmpty() -> "Add light so the parts and labels are visible."
                motion > 32 -> "Move more slowly so I can keep track of the connection."
                quality.sharpness < 3 && guidance.isEmpty() -> "Hold steady and bring the equipment into focus."
                guidance.isNotEmpty() -> if (metricAdvice != null) "$guidance ($metricAdvice)" else guidance
                metricAdvice != null -> metricAdvice
                mergedObjects.isNotEmpty() -> "Keep the connection visible. Capture a photo when ready."
                else -> "Aim at the equipment and hold steady for detection."
            }
            val anchorCount = if (spatialMode) spatialTracker.activeAnchors.size else 0
            val statusText = error.ifBlank {
                if (mergedObjects.isNotEmpty()) {
                    val modeTag = if (spatialMode && mergedObjects.any { it.spatialAnchored }) " · 3D world-anchored" else ""
                    "Tracking ${mergedObjects.size} part${if (mergedObjects.size == 1) "" else "s"}$modeTag"
                } else if (spatialMode && anchorCount > 0) {
                    "$anchorCount 3D anchor${if (anchorCount == 1) "" else "s"} locked off-screen · pan back to view"
                } else if ("detect" in busy) "Finding parts…"
                else if (detected) "Tracking lost · finding parts again"
                else if (scanned) "No parts found · keep scanning"
                else "Looking for equipment…"
            }
            mutable.value = LiveCameraState(
                objects = mergedObjects,
                status = statusText,
                recommendation = advice,
                width = bitmap.width,
                height = bitmap.height,
                guidanceAge = guidanceAt.takeIf { it > 0 }?.let { (now - it) / 1000 },
                fps = fps,
                spatialMode = spatialMode,
                activeAnchors = anchorCount,
                brightness = quality.brightness,
                sharpness = quality.sharpness,
                motion = motion,
            )
            listOf("detect", "guide").filter { it !in busy && now >= (due[it] ?: 0) }.also { busy.addAll(it) }
        }
        if (kinds.isEmpty()) return
        val output = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output); val bytes = output.toByteArray()
        for (kind in kinds) scope.launch {
            val interval = if (kind == "detect") 4000L else 12000L
            synchronized(lock) { due[kind] = now + interval }
            try {
                val result = withTimeout(22000) { analyze(kind, bytes, frame.id) }
                synchronized(lock) {
                    if (!closed && result.frameId == frame.id && SystemClock.elapsedRealtime() - now < 20000) {
                        error = ""; due[kind] = SystemClock.elapsedRealtime() + maxOf(interval, result.refreshAfterMs.toLong())
                        if (kind == "detect") {
                            val ok2d = tracker.seed(frame.id, result.objects)
                            val ok3d = spatialTracker.seedFromSam(frame.id, result.objects)
                            scanned = ok2d || ok3d || scanned
                            detected = tracker.objects.isNotEmpty() || spatialTracker.activeAnchors.isNotEmpty() || detected
                        } else if (tracker.current(frame.id)) {
                            guidance = result.recommendation.orEmpty(); guidanceFrame = frame.id; guidanceAt = now
                        }
                    }
                }
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException) throw e
                synchronized(lock) { error = "AI timed out · retrying"; due[kind] = SystemClock.elapsedRealtime() + 5000 }
            } catch (e: Exception) {
                val retry = (e as? HttpException)?.response()?.headers()?.get("Retry-After")?.toLongOrNull()?.times(1000) ?: 5000
                synchronized(lock) { error = if (e is HttpException && e.code() == 429) "AI cooling down · local tracking continues" else "AI unavailable · retrying"; due[kind] = SystemClock.elapsedRealtime() + retry.coerceAtLeast(1000) }
            } finally { synchronized(lock) { busy.remove(kind) } }
        }
    }
}
