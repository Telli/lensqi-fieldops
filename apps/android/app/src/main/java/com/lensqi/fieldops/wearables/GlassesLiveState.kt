package com.lensqi.fieldops.wearables

import com.lensqi.fieldops.ui.camera.LiveCameraState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class GlassesLiveState(val inspectionId: String? = null, val enabled: Boolean = false, val muted: Boolean = false,
    val feedback: LiveCameraState = LiveCameraState(), val audioNote: String? = null)

/** Service-owned state survives a hidden or recreated screen. No frames are retained here. */
@Singleton class GlassesLiveStore @Inject constructor() {
    internal val mutable = MutableStateFlow(GlassesLiveState())
    val state = mutable.asStateFlow()
}
