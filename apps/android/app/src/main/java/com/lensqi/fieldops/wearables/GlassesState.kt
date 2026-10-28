package com.lensqi.fieldops.wearables

import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputSource

/**
 * Which glasses produced a photo. The value of [evidenceOrigin] is the API's `EvidenceOrigin` name sent as the upload's
 * `source`: real Ray-Ban Meta photos are "Glasses"; anything from Meta's Mock Device Kit is "MockWearables" and is never
 * labelled as glasses evidence.
 */
enum class GlassesMode(val evidenceOrigin: String, val label: String) {
    Real("Glasses", "Ray-Ban Meta"),
    Mock("MockWearables", "Mock glasses"),
}

/** The one status chip the glasses screen shows. */
enum class GlassesPhase(val label: String) {
    Unavailable("Glasses unavailable"),
    NotRegistered("Not registered"),
    Registering("Registering"),
    NoGlasses("No glasses connected"),
    Connected("Glasses connected"),
    Starting("Starting camera"),
    Paused("Paused on glasses"),
    Streaming("Streaming"),
}

/** Everything the glasses screen renders; produced only by [GlassesSource]. */
data class GlassesState(
    /** `Wearables.initialize` succeeded (or the Mock Device Kit initialised it). */
    val sdkReady: Boolean = false,
    val mode: GlassesMode = GlassesMode.Real,
    /** Debug builds carry the Mock Device Kit; release builds never do. */
    val mockAvailable: Boolean = false,
    val registration: RegistrationState? = null,
    val deviceName: String? = null,
    val deviceConnected: Boolean = false,
    val displayCapable: Boolean = false,
    val session: DeviceSessionState? = null,
    val stream: StreamState? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    /** The instruction is on a Meta Ray-Ban Display (only ever true for display-capable glasses). */
    val displayActive: Boolean = false,
    /** The glasses' capture button / touchpad reach the app (DAT Inputs capability attached and active). */
    val glassesButtonActive: Boolean = false,
    val glassesButtonNote: String? = null,
    val mockCapturedImageSet: Boolean = false,
    val mockFeed: String? = null,
    val capturing: Boolean = false,
    val error: String? = null,
) {
    val phase: GlassesPhase get() = glassesPhase(sdkReady, registration, deviceConnected, session, stream)
    val streaming: Boolean get() = stream == StreamState.STREAMING
}

/** Pure mapping from the SDK's state machines to the chip; the SDK, not the screen, decides each input. */
fun glassesPhase(
    sdkReady: Boolean,
    registration: RegistrationState?,
    deviceConnected: Boolean,
    session: DeviceSessionState?,
    stream: StreamState?,
): GlassesPhase = when {
    !sdkReady || registration == null || registration == RegistrationState.UNAVAILABLE -> GlassesPhase.Unavailable
    registration == RegistrationState.REGISTERING -> GlassesPhase.Registering
    registration != RegistrationState.REGISTERED -> GlassesPhase.NotRegistered
    stream == StreamState.STREAMING -> GlassesPhase.Streaming
    stream == StreamState.PAUSED || session == DeviceSessionState.PAUSED -> GlassesPhase.Paused
    stream == StreamState.STARTING || stream == StreamState.STARTED || session == DeviceSessionState.STARTING -> GlassesPhase.Starting
    deviceConnected -> GlassesPhase.Connected
    else -> GlassesPhase.NoGlasses
}

/** A short press of the glasses' capture button, or a select on the touchpad, takes the photo. Holds and doubles do not. */
fun isShutterPress(event: InputEvent): Boolean = when (event) {
    is InputEvent.Capture -> event.pressType == CapturePressType.SHORT_PRESS
    is InputEvent.Select -> event.source == InputSource.CAPTOUCH
    else -> false
}
