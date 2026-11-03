package com.lensqi.fieldops.ui.glasses

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.view.Surface
import android.view.View
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lensqi.fieldops.wearables.GlassesPhoto
import com.lensqi.fieldops.wearables.GlassesSource
import com.lensqi.fieldops.wearables.GlassesLiveService
import com.lensqi.fieldops.wearables.GlassesLiveStore
import com.lensqi.fieldops.wearables.LiveSession
import com.lensqi.fieldops.wearables.shouldResumeLive
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class GlassesViewModel @Inject constructor(
    private val source: GlassesSource,
    private val liveStore: GlassesLiveStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val state = source.state
    val shutter = source.shutter
    val live = liveStore.state

    fun initialize() = source.initialize()
    fun register(activity: Activity) = source.startRegistration(activity)
    suspend fun cameraPermissionGranted() = source.cameraPermissionGranted()
    fun start() = source.startStreaming()
    fun stop() { pauseLive(); source.stopStreaming() }
    fun startLive(session: LiveSession) {
        runCatching { GlassesLiveService.start(context, session) }
            .onFailure { source.reportError("Could not start live assistance. Return to the glasses screen and try again.") }
    }
    fun pauseLive() { if (live.value.enabled) GlassesLiveService.pause(context) }
    fun muteLive() = GlassesLiveService.mute(context)
    fun setSurface(surface: Surface?) = source.setPreviewSurface(surface)
    fun showHud(prompt: String, status: String?) = source.showHud(prompt, status)
    fun report(message: String?) = source.reportError(message)

    /**
     * Takes the glasses photo. Live assist pauses for the shutter; when it was running for [session], the glasses screen
     * starts it again the next time it shows that session with the camera streaming (see [consumeResume]).
     */
    fun capture(session: LiveSession? = null, onCaptured: (GlassesPhoto) -> Unit) {
        if (state.value.capturing || !state.value.streaming) return
        val resume = session != null && live.value.enabled && session.sameAs(live.value.session)
        pauseLive()
        viewModelScope.launch {
            runCatching { source.capturePhoto() }
                .onSuccess { photo ->
                    // Recorded only once the photo is in hand: set before the pause it would restart the service mid-capture
                    // (the screen's resume effect fires on the pause), and the pop would then kill it with nothing left to resume.
                    if (resume) liveStore.resumeFor.value = session
                    onCaptured(photo)
                }
                .onFailure {
                    if (resume) liveStore.resumeFor.value = session
                    source.reportError("The photo couldn’t be taken. Check the glasses connection and try again.")
                }
        }
    }

    /** True once, when live assist should start again for [session] after a shutter photo paused it; never during a capture. */
    fun consumeResume(session: LiveSession): Boolean {
        val s = state.value
        if (!shouldResumeLive(s.streaming, live.value.enabled, s.capturing, liveStore.resumeFor.value, session)) return false
        liveStore.resumeFor.value = null
        return true
    }

    fun enableMock(withDisplay: Boolean) { stop(); source.enableMock(withDisplay) }
    fun disableMock() { stop(); source.disableMock() }
    fun useMockPhoneCameraFeed() = source.useMockPhoneCameraFeed()
    fun pressMockCaptureButton() = source.pressMockCaptureButton()
    fun mockDisplayPreview(context: Context): View? = source.mockDisplayPreview(context)

    /** Copies the picked image into app storage so the in-process mock can read it, then makes it the mock's next capture. */
    fun setMockCapturedImage(uri: Uri) = copyThen(uri, "mock-glasses-capture.img", source::setMockCapturedImage)

    /** Same for a video clip used as the mock glasses' camera feed. */
    fun setMockVideoFeed(uri: Uri) = copyThen(uri, "mock-glasses-feed.mp4", source::setMockVideoFeed)

    private fun copyThen(uri: Uri, name: String, use: (Uri) -> Unit) = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                File(context.cacheDir, name).also { f ->
                    context.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
                }
            }
        }.onSuccess { use(Uri.fromFile(it)) }
            .onFailure { source.reportError("This file couldn’t be opened. Choose another photo or video.") }
    }

    /**
     * The screen is gone, not the work: live assist belongs to the foreground service, which keeps the stream for as long as
     * it runs (its notification's Stop ends it). Only a stream nobody else owns is stopped here.
     */
    override fun onCleared() {
        source.setPreviewSurface(null)
        if (!live.value.enabled) source.stopStreaming()
    }
}
