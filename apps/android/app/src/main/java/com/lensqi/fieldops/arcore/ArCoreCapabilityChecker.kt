package com.lensqi.fieldops.arcore

import android.content.Context
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteOrder

data class ArCoreCapability(
    val available: Boolean,
    val depthSupported: Boolean,
    val statusLabel: String,
)

/**
 * Checks device support for Google Play Services for AR (ARCore) and hardware/motion Depth API, and extracts
 * 6DoF camera poses + 16-bit metric depth grids from ARCore frames when active.
 */
object ArCoreCapabilityChecker {
    fun check(context: Context): ArCoreCapability = runCatching {
        val availability = ArCoreApk.getInstance().checkAvailability(context)
        when {
            availability.isSupported -> ArCoreCapability(
                available = true,
                depthSupported = true,
                statusLabel = "ARCore 3D world-anchored · depth ready",
            )
            availability.isTransient -> ArCoreCapability(
                available = false,
                depthSupported = false,
                statusLabel = "Checking ARCore support…",
            )
            else -> ArCoreCapability(
                available = false,
                depthSupported = false,
                statusLabel = "2D patch tracking (ARCore unavailable on this device)",
            )
        }
    }.getOrElse {
        ArCoreCapability(
            available = false,
            depthSupported = false,
            statusLabel = "2D patch tracking",
        )
    }

    /** Configures an ARCore [Session] with automatic Depth API when supported by the device. */
    fun configureSession(session: Session): Boolean {
        val depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        val config = session.config.apply {
            focusMode = Config.FocusMode.AUTO
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            depthMode = if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
        }
        session.configure(config)
        return depthSupported
    }

    /** Extracts a [PoseSnapshot] with 6DoF camera pose, intrinsics, and a downsampled metric depth grid from an ARCore [Frame]. */
    fun snapshotFromFrame(frameId: String, timeMs: Long, frame: Frame): PoseSnapshot? {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return null
        val p = camera.pose
        val intrinsics = camera.imageIntrinsics
        val dims = intrinsics.imageDimensions
        val fx = if (dims[0] > 0) intrinsics.focalLength[0].toDouble() / dims[0] else 0.86
        val fy = if (dims[1] > 0) intrinsics.focalLength[1].toDouble() / dims[1] else 1.15
        val pose = Pose6DoF(
            tx = p.tx().toDouble(),
            ty = p.ty().toDouble(),
            tz = p.tz().toDouble(),
            qx = p.qx().toDouble(),
            qy = p.qy().toDouble(),
            qz = p.qz().toDouble(),
            qw = p.qw().toDouble(),
            fx = fx,
            fy = fy,
        )
        var grid: FloatArray? = null
        var gw = 0
        var gh = 0
        var centerDepth = 0.35
        runCatching {
            frame.acquireDepthImage16Bits().use { depthImage ->
                val w = depthImage.width
                val h = depthImage.height
                val plane = depthImage.planes[0]
                val buffer = plane.buffer.order(ByteOrder.nativeOrder())
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                gw = 16
                gh = 12
                val sampled = FloatArray(gw * gh)
                for (gy in 0 until gh) {
                    val y = (gy * h) / gh
                    for (gx in 0 until gw) {
                        val x = (gx * w) / gw
                        val idx = y * rowStride + x * pixelStride
                        val mm = buffer.getShort(idx).toInt() and 0xFFFF
                        sampled[gy * gw + gx] = if (mm in 100..5000) mm / 1000f else Float.NaN
                    }
                }
                grid = sampled
                val centerMm = buffer.getShort((h / 2) * rowStride + (w / 2) * pixelStride).toInt() and 0xFFFF
                if (centerMm in 100..5000) centerDepth = centerMm / 1000.0
            }
        }
        return PoseSnapshot(
            frameId = frameId,
            timeMs = timeMs,
            pose = pose,
            defaultDepthMeters = centerDepth,
            depthGridMeters = grid,
            gridWidth = gw,
            gridHeight = gh,
        )
    }
}
