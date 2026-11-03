package com.lensqi.fieldops.wearables

import com.lensqi.fieldops.ui.camera.LiveCameraState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class GlassesLiveState(val session: LiveSession? = null, val enabled: Boolean = false, val muted: Boolean = false,
    val feedback: LiveCameraState = LiveCameraState(), val audioNote: String? = null)

/** Service-owned state survives a hidden or recreated screen. No frames are retained here. */
@Singleton class GlassesLiveStore @Inject constructor() {
    internal val mutable = MutableStateFlow(GlassesLiveState())
    val state = mutable.asStateFlow()
    /** The session live assist was running for when a shutter photo paused it; the glasses screen starts it again for that session. */
    internal val resumeFor = MutableStateFlow<LiveSession?>(null)
}

/**
 * Whether the glasses screen should start live assist again now for [session]: the camera streams, no service runs, no
 * shutter photo is in flight, and the pause was for this very session. Never while capturing: the restart would race the
 * photo and consume the pending resume before the next screen could.
 */
fun shouldResumeLive(streaming: Boolean, liveEnabled: Boolean, capturing: Boolean, pending: LiveSession?, session: LiveSession): Boolean =
    streaming && !liveEnabled && !capturing && pending != null && session.sameAs(pending)
