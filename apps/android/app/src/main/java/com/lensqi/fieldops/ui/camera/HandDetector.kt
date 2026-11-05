package com.lensqi.fieldops.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.lensqi.fieldops.data.diagnostics.DiagnosticHistory
import com.lensqi.fieldops.domain.live.HandPoint
import com.lensqi.fieldops.domain.live.HandReading

/**
 * MediaPipe's Hand Landmarker, on the phone's CPU, on whichever thread analyses the camera. Nothing it sees leaves the phone.
 * Opened on the first frame, not on the main thread. If it can't run here (a 32-bit phone, a build without the model), it
 * says so once through [failed] and finds no hands from then on: the camera works exactly as it does without it.
 *
 * Native code can die in a way no catch sees, and the camera would then die on every visit. So a mark is written before the
 * first detection and cleared after it; a mark still there on the next visit means it took the app down, and it stays off
 * until the person turns the setting off and on again ([retry]), which also recovers from an unrelated kill in that moment.
 */
class HandDetector(private val context: Context) {
    private var landmarker: HandLandmarker? = null
    private var lastTime = -1L
    private var closed = false
    private var proven = false
    private val marks = context.getSharedPreferences("hand_detector", Context.MODE_PRIVATE)
    @Volatile var failed = marks.getBoolean(STARTING, false); private set

    /** The hands in [bitmap], which must be upright ARGB_8888. Fractions refer to this bitmap. Usually none. */
    @Synchronized
    fun detect(bitmap: Bitmap, now: Long): List<HandReading> {
        if (closed || failed) return emptyList()
        return try {
            if (!proven) marks.edit().putBoolean(STARTING, true).commit()
            val running = landmarker ?: open().also { landmarker = it }
            // The task refuses a timestamp that doesn't increase.
            val time = maxOf(now, lastTime + 1).also { lastTime = it }
            BitmapImageBuilder(bitmap).build().use { image ->
                val result = running.detectForVideo(image, time)
                if (!proven) { proven = true; marks.edit().remove(STARTING).commit() }
                result.landmarks().indices.map { i ->
                    HandReading(
                        result.landmarks()[i].map { HandPoint(it.x().toDouble(), it.y().toDouble(), it.z().toDouble()) },
                        result.worldLandmarks().getOrNull(i).orEmpty().map { HandPoint(it.x().toDouble(), it.y().toDouble(), it.z().toDouble()) },
                        result.handedness().getOrNull(i)?.firstOrNull()?.score()?.toDouble() ?: 0.0,
                    )
                }
            }
        } catch (t: Throwable) {
            // UnsatisfiedLinkError as well as MediaPipe's own: pointing is optional, and a camera must never crash for it.
            Log.w("HandDetector", "hand detection is off: $t")
            failed = true
            // Written now: a write left for later is lost if the process ends first, and pointing would stay off for good.
            marks.edit().remove(STARTING).commit()
            DiagnosticHistory.record("camera", "pointing_unavailable")
            emptyList()
        }
    }

    private fun open(): HandLandmarker = HandLandmarker.createFromOptions(context, HandLandmarker.HandLandmarkerOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(if (emulatorClaimingSme()) Delegate.GPU else Delegate.CPU).build())
        .setRunningMode(RunningMode.VIDEO)
        // Two: one hand often holds the thing the other points at.
        .setNumHands(2)
        .setMinHandDetectionConfidence(0.5f).setMinHandPresenceConfidence(0.5f).setMinTrackingConfidence(0.5f)
        .build())

    @Synchronized
    fun close() {
        closed = true
        runCatching { landmarker?.close() }
        landmarker = null
    }

    companion object {
        private const val MODEL = "models/hand_landmarker.task"
        private const val STARTING = "starting"

        /** The person asked for it again: forget that it stopped the app last time. */
        fun retry(context: Context) { context.getSharedPreferences("hand_detector", Context.MODE_PRIVATE).edit().remove(STARTING).commit() }

        /**
         * An Arm emulator on an Apple M4 advertises SME but faults on its instructions (SIGILL on `rdsvl`), and MediaPipe's CPU
         * path believes the flag. Phones are not affected: one that advertises SME has it. Only the emulator goes to the GPU.
         */
        private fun emulatorClaimingSme(): Boolean = Build.HARDWARE in setOf("ranchu", "goldfish") &&
            runCatching { java.io.File("/proc/cpuinfo").useLines { lines -> lines.any { it.startsWith("Features") && " sme" in it } } }.getOrDefault(false)
    }
}
