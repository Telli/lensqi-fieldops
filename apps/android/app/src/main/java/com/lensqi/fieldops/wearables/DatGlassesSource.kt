package com.lensqi.fieldops.wearables

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.View
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.types.DisplayConfiguration
import com.meta.wearable.dat.display.types.DisplayState
import com.meta.wearable.dat.display.views.ButtonStyle
import com.meta.wearable.dat.display.views.Direction
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.types.InputSource
import com.meta.wearable.dat.inputs.types.InputsConfiguration
import com.meta.wearable.dat.inputs.types.InputsState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** [GlassesSource] over the Meta Wearables Device Access Toolkit 1.0.1. One session at a time, owned by the glasses screen. */
@Singleton
class DatGlassesSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mock: MockGlasses,
) : GlassesSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val preview = HevcPreview()

    private val _state = MutableStateFlow(GlassesState(mockAvailable = mock.available))
    override val state: StateFlow<GlassesState> = _state.asStateFlow()
    private val _shutter = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val shutter = _shutter.asSharedFlow()

    private var monitor: Job? = null
    private var connection: CoroutineScope? = null
    private var session: DeviceSession? = null
    private var stream: Stream? = null
    private var display: Display? = null
    private var hud: Pair<String, String?>? = null

    override fun initialize(): Boolean {
        if (_state.value.sdkReady) return true
        val ok = Wearables.initialize(context).fold(onSuccess = { true }, onFailure = { error, _ -> reportError(error.description); false })
        if (ok) onSdkReady()
        return ok
    }

    private fun onSdkReady() {
        _state.update { it.copy(sdkReady = true) }
        if (monitor != null) return
        monitor = scope.launch {
            launch { Wearables.registrationState.collect { r -> _state.update { it.copy(registration = r) } } }
            launch { Wearables.registrationErrorStream.collect { e -> reportError(e.description) } }
            launch { connectedDevices().collect(::onDevices) }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun connectedDevices() = Wearables.devices.flatMapLatest { ids ->
        val flows = ids.mapNotNull { Wearables.devicesMetadata[it] }
        if (flows.isEmpty()) flowOf(emptyList()) else combine(flows) { it.toList() }
    }

    private fun onDevices(devices: List<Device>) {
        val device = devices.firstOrNull { it.linkState == LinkState.CONNECTED } ?: devices.firstOrNull()
        _state.update {
            it.copy(
                deviceName = device?.name?.ifBlank { device.deviceType.description },
                deviceConnected = device?.linkState == LinkState.CONNECTED,
                displayCapable = device?.isDisplayCapable() == true,
            )
        }
    }

    override fun startRegistration(activity: Activity) {
        _state.update { it.copy(error = null) }
        Wearables.startRegistration(activity)
    }

    override suspend fun cameraPermissionGranted(): Boolean =
        Wearables.checkPermissionStatus(Permission.CAMERA).fold(
            onSuccess = { it == PermissionStatus.Granted },
            onFailure = { error, _ -> reportError(error.description); false },
        )

    override fun startStreaming() {
        // A session whose stream already ended (a critical stream error, or the wearer stopped it) cannot be restarted:
        // stop it and open a fresh one.
        if (session != null) {
            if (_state.value.stream.let { it == StreamState.STREAMING || it == StreamState.STARTING || it == StreamState.STARTED || it == StreamState.PAUSED }) return
            stopStreaming()
        }
        _state.update { it.copy(error = null) }
        Wearables.createSession(AutoDeviceSelector()).fold(
            onSuccess = { created ->
                session = created
                val conn = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
                connection = conn
                // Subscribe before start() so no transition is missed.
                conn.launch { created.errors.collect { reportError(it.description) } }
                conn.launch {
                    created.state.collect { s ->
                        _state.update { it.copy(session = s) }
                        when (s) {
                            DeviceSessionState.STARTED -> if (stream == null) attach(created, conn)
                            DeviceSessionState.STOPPED -> teardown()
                            else -> Unit
                        }
                    }
                }
                created.start()
            },
            onFailure = { error, _ -> reportError(error.description) },
        )
    }

    /** Capabilities may only be added once the session reports STARTED. */
    private fun attach(s: DeviceSession, conn: CoroutineScope) {
        s.addCamera(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = FRAME_RATE, compressVideo = true)).fold(
            onSuccess = { camera ->
                val st = camera.stream
                stream = st
                conn.launch(frameDispatcher) {
                    var logged = false
                    st.videoStream.collect { frame ->
                        val buffer = frame.buffer.duplicate()
                        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                        if (!logged) { logged = true; Log.i(TAG, "first frame ${frame.width}x${frame.height} compressed=${frame.isCompressed} config=${frame.isCodecConfig} ${bytes.size} bytes") }
                        if (frame.isCompressed) preview.feed(bytes, frame.width, frame.height, frame.presentationTimeUs, frame.isCodecConfig)
                        if (!frame.isCodecConfig && (_state.value.frameWidth != frame.width || _state.value.frameHeight != frame.height)) {
                            _state.update { it.copy(frameWidth = frame.width, frameHeight = frame.height) }
                        }
                    }
                }
                conn.launch { st.state.collect { ss -> _state.update { it.copy(stream = ss) } } }
                conn.launch { st.errorStream.collect { reportError(it.description) } }
                st.start().onFailure { error, _ -> reportError(error.description) }
            },
            onFailure = { error, _ -> reportError(error.description) },
        )
        attachInputs(s, conn)
        if (_state.value.displayCapable) attachDisplay(s, conn)
    }

    /** The capture button / touchpad. DAT Inputs is experimental and needs approval in the Wearables Developer Center. */
    private fun attachInputs(s: DeviceSession, conn: CoroutineScope) {
        s.addInputs(InputsConfiguration(sources = setOf(InputSource.CAPTURE_BUTTON, InputSource.CAPTOUCH), consumeBack = false)).fold(
            onSuccess = { inputs ->
                conn.launch { inputs.state.collect { st -> _state.update { it.copy(glassesButtonActive = st == InputsState.ACTIVE, glassesButtonNote = null) } } }
                conn.launch { inputs.errors.collect { e -> if (e != null) _state.update { it.copy(glassesButtonNote = e.description) } } }
                conn.launch { inputs.events.collect { if (isShutterPress(it)) _shutter.tryEmit(Unit) } }
            },
            onFailure = { error, _ -> _state.update { it.copy(glassesButtonActive = false, glassesButtonNote = error.description) } },
        )
    }

    /** Only Meta Ray-Ban Display glasses have a display; for every other pair this is never called. */
    private fun attachDisplay(s: DeviceSession, conn: CoroutineScope) {
        s.addDisplay(DisplayConfiguration()).fold(
            onSuccess = { d ->
                display = d
                conn.launch {
                    d.state.collect { ds ->
                        if (ds != DisplayState.STARTED) _state.update { it.copy(displayActive = false) }
                        if (ds == DisplayState.STARTED) pushHud()
                    }
                }
            },
            onFailure = { error, _ -> reportError("Glasses display: ${error.description}") },
        )
    }

    override fun showHud(prompt: String, status: String?) {
        hud = prompt to status
        pushHud()
    }

    private fun pushHud() {
        val d = display ?: return
        val (prompt, status) = hud ?: return
        if (d.state.value != DisplayState.STARTED) return
        (connection ?: return).launch {
            d.sendContent {
                flexBox(direction = Direction.COLUMN, gap = 10, padding = 24, background = FlexBoxBackground.CARD) {
                    text("LensQi FieldOps", style = TextStyle.META, color = TextColor.SECONDARY)
                    text(prompt, style = TextStyle.HEADING)
                    status?.let { text(it, style = TextStyle.BODY, color = TextColor.SECONDARY) }
                    button("Capture", style = ButtonStyle.PRIMARY, onClick = { _shutter.tryEmit(Unit) })
                }
            }.fold(
                onSuccess = { _state.update { it.copy(displayActive = true) } },
                onFailure = { error, _ -> _state.update { it.copy(displayActive = false) }; reportError("Glasses display: ${error.description}") },
            )
        }
    }

    override fun stopStreaming() {
        session?.stop()
        teardown()
    }

    private fun teardown() {
        connection?.cancel()
        connection = null
        stream = null
        display = null
        session = null
        preview.release()
        _state.update { it.copy(session = null, stream = null, displayActive = false, glassesButtonActive = false, capturing = false) }
    }

    override fun setPreviewSurface(surface: Surface?) = preview.setSurface(surface)

    override suspend fun capturePhoto(): GlassesPhoto {
        val st = stream ?: error("Start the glasses camera first")
        val mode = _state.value.mode // decided before the capture: a mock photo is never relabelled
        _state.update { it.copy(capturing = true, error = null) }
        try {
            val data = st.capturePhoto().fold(onSuccess = { it }, onFailure = { e, _ -> throw IllegalStateException(e.description) })
            val file = withContext(Dispatchers.Default) { GlassesPhotos.writeJpeg(data, context.cacheDir) }
            return GlassesPhoto(file, mode)
        } finally {
            _state.update { it.copy(capturing = false) }
        }
    }

    override fun reportError(message: String?) {
        if (message != null) Log.w(TAG, message)
        _state.update { it.copy(error = message) }
    }

    override fun enableMock(withDisplay: Boolean) {
        stopStreaming()
        val error = mock.enable(withDisplay)
        if (error != null) { reportError("Mock glasses: $error"); return }
        // The kit initialises Wearables itself and fakes registration, device link and DAT permissions.
        _state.update { it.copy(mode = GlassesMode.Mock, mockCapturedImageSet = false, mockFeed = null, error = null) }
        onSdkReady()
    }

    override fun disableMock() {
        stopStreaming()
        mock.disable()
        _state.update { it.copy(mode = GlassesMode.Real, mockCapturedImageSet = false, mockFeed = null) }
    }

    override fun setMockCapturedImage(uri: Uri) {
        runCatching { mock.setCapturedImage(uri) }
            .onSuccess { _state.update { it.copy(mockCapturedImageSet = true) } }
            .onFailure { reportError("Mock glasses: ${it.message}") }
    }

    override fun setMockVideoFeed(uri: Uri) {
        val error = mock.setVideoFeed(uri)
        if (error != null) reportError("Mock feed: $error") else _state.update { it.copy(mockFeed = "Video clip") }
    }

    override fun useMockPhoneCameraFeed() {
        val error = mock.usePhoneCameraFeed()
        if (error != null) reportError("Mock feed: $error") else _state.update { it.copy(mockFeed = "Phone camera") }
    }

    override fun pressMockCaptureButton() = mock.pressCaptureButton()

    override fun mockDisplayPreview(context: Context): View? = if (mock.enabled) mock.displayPreview(context) else null

    private companion object {
        const val TAG = "GlassesSource"
        const val FRAME_RATE = 24
    }
}
