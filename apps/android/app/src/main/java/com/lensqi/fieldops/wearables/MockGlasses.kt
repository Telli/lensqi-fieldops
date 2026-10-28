package com.lensqi.fieldops.wearables

import android.content.Context
import android.net.Uri
import android.view.View

/**
 * Meta's Mock Device Kit behind a seam: the debug source set wraps the real kit (`mwdat-mockdevice`), the release source
 * set has a stub with [available] = false, so a release build cannot produce "MockWearables" evidence.
 * Everything that comes out of an enabled mock is labelled [GlassesMode.Mock].
 */
interface MockGlasses {
    val available: Boolean
    val enabled: Boolean
    val hasDisplay: Boolean

    /** Enables the kit (registered, DAT permissions granted) and pairs one powered-on, unfolded, worn pair. Returns an error or null. */
    fun enable(withDisplay: Boolean): String?
    fun disable()

    /** The image the mock returns from `capturePhoto()` (a photo the technician picked). */
    fun setCapturedImage(uri: Uri)

    /** Streams a video (H.264/H.265) as the mock glasses' camera feed, e.g. a clip made from a lab render. */
    fun setVideoFeed(uri: Uri): String?

    /** Streams the phone's back camera as the mock glasses' video feed (needs the handset CAMERA permission). */
    fun usePhoneCameraFeed(): String?

    /** Simulates a short press of the glasses' capture button (delivered through the DAT Inputs capability). */
    fun pressCaptureButton()

    /** What a Meta Ray-Ban Display would show, rendered by the kit; null for glasses without a display. */
    fun displayPreview(context: Context): View?
}
