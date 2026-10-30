package com.lensqi.fieldops.wearables

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.view.Surface
import android.view.View
import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** A photo taken on the glasses: an upright JPEG in cacheDir and the evidence origin it must be uploaded with. */
data class GlassesPhoto(val file: File, val mode: GlassesMode) {
    val origin: String get() = mode.evidenceOrigin
}

/**
 * The PRD's frame source, for Meta glasses through the Wearables Device Access Toolkit: registration, device and stream
 * state, a live preview, photo capture and the glasses' own shutter. Real glasses and the Mock Device Kit go through the
 * same SDK calls; [GlassesState.mode] says which one is answering.
 */
interface GlassesSource {
    val state: StateFlow<GlassesState>

    /** Shutter requests from the glasses themselves: capture button, touchpad select, or the Display's Capture button. */
    val shutter: Flow<Unit>

    /** `Wearables.initialize`; call after the Bluetooth permission is granted. Idempotent. */
    fun initialize(): Boolean

    /** Hands over to the Meta AI app, which asks the wearer to connect LensQi FieldOps. */
    fun startRegistration(activity: Activity)

    suspend fun cameraPermissionGranted(): Boolean

    /** Opens a device session and, once it starts, the camera stream (plus Inputs and, on Display glasses, the HUD). */
    fun startStreaming()
    fun stopStreaming()

    fun setPreviewSurface(surface: Surface?)

    /** Decoded live frames, independent of the phone preview. Borrowed Bitmap; do not retain or recycle it. */
    fun setFrameConsumer(consumer: ((Bitmap) -> Unit)?)

    /** Captures from the active stream; throws with the SDK's description on failure. */
    suspend fun capturePhoto(): GlassesPhoto

    /** The instruction for the wearer, shown on a Meta Ray-Ban Display when one is connected. */
    fun showHud(prompt: String, status: String?)

    fun reportError(message: String?)

    // Mock Device Kit (debug builds).
    fun enableMock(withDisplay: Boolean)
    fun disableMock()
    fun setMockCapturedImage(uri: Uri)
    fun setMockVideoFeed(uri: Uri)
    fun useMockPhoneCameraFeed()
    fun pressMockCaptureButton()
    fun mockDisplayPreview(context: Context): View?
}
