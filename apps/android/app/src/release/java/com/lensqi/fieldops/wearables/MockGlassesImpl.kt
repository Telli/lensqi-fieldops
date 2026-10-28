package com.lensqi.fieldops.wearables

import android.content.Context
import android.net.Uri
import android.view.View
import javax.inject.Inject
import javax.inject.Singleton

/** Release builds carry no Mock Device Kit: mock glasses cannot be enabled, so no "MockWearables" evidence is possible. */
@Singleton
class MockGlassesImpl @Inject constructor() : MockGlasses {
    override val available = false
    override val enabled = false
    override val hasDisplay = false
    override fun enable(withDisplay: Boolean): String = "Mock glasses are only in debug builds"
    override fun disable() = Unit
    override fun setCapturedImage(uri: Uri) = error("Mock glasses are only in debug builds")
    override fun setVideoFeed(uri: Uri): String = "Mock glasses are only in debug builds"
    override fun usePhoneCameraFeed(): String = "Mock glasses are only in debug builds"
    override fun pressCaptureButton() = Unit
    override fun displayPreview(context: Context): View? = null
}
