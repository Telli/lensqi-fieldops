package com.lensqi.fieldops.wearables

import android.content.Context
import android.net.Uri
import android.view.View
import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import com.meta.wearable.dat.mockdevice.api.MockGlasses as KitGlasses

/** Debug builds only: Meta's Mock Device Kit simulating one pair of glasses inside this app's process. */
@Singleton
class MockGlassesImpl @Inject constructor(@ApplicationContext private val context: Context) : MockGlasses {
    private val kit get() = MockDeviceKit.getInstance(context)
    private var device: KitGlasses? = null

    override val available = true
    override val enabled get() = kit.isEnabled && device != null
    override var hasDisplay = false
        private set

    override fun enable(withDisplay: Boolean): String? {
        disable()
        kit.enable(MockDeviceKitConfig(initiallyRegistered = true, initialPermissionsGranted = true))
        val model = if (withDisplay) GlassesModel.META_RAYBAN_DISPLAY else GlassesModel.RAYBAN_META
        return kit.pairGlasses(model).fold(
            onSuccess = { glasses ->
                glasses.powerOn()
                glasses.unfold()
                glasses.don()
                device = glasses
                hasDisplay = withDisplay
                null
            },
            onFailure = { error, _ -> kit.disable(); error.description },
        )
    }

    override fun disable() {
        device?.let { runCatching { kit.unpairDevice(it) } }
        device = null
        hasDisplay = false
        if (kit.isEnabled) kit.disable()
    }

    override fun setCapturedImage(uri: Uri) {
        (device ?: error("Mock glasses are not enabled")).services.camera.setCapturedImage(uri)
    }

    override fun setVideoFeed(uri: Uri): String? =
        runCatching { (device ?: error("Mock glasses are not enabled")).services.camera.setCameraFeed(uri) }.exceptionOrNull()?.message

    override fun usePhoneCameraFeed(): String? =
        runCatching { (device ?: error("Mock glasses are not enabled")).services.camera.setCameraFeed(CameraFacing.BACK) }.exceptionOrNull()?.message

    override fun pressCaptureButton() {
        device?.services?.input?.capture(CapturePressType.SHORT_PRESS)
    }

    override fun displayPreview(context: Context): View? = if (hasDisplay) device?.services?.display?.createPreviewView(context) else null
}
