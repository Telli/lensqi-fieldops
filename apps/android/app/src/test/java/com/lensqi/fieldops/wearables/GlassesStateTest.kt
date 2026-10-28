package com.lensqi.fieldops.wearables

import com.lensqi.fieldops.data.api.EvidenceOrigin
import com.lensqi.fieldops.ui.common.originLabel
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputSource
import com.meta.wearable.dat.inputs.types.NavDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GlassesStateTest {
    @Test
    fun real_glasses_upload_as_Glasses_and_the_mock_as_MockWearables() {
        assertEquals("Glasses", GlassesMode.Real.evidenceOrigin)
        assertEquals("MockWearables", GlassesMode.Mock.evidenceOrigin)
        // The strings are the API's EvidenceOrigin names (the server parses `source` into that enum, defaulting to Phone).
        GlassesMode.entries.forEach { assertEquals(it.evidenceOrigin, EvidenceOrigin.valueOf(it.evidenceOrigin).name) }
    }

    @Test
    fun mock_output_is_never_labelled_as_glasses_or_phone() {
        assertNotEquals(GlassesMode.Real.evidenceOrigin, GlassesMode.Mock.evidenceOrigin)
        assertTrue(GlassesMode.Mock.evidenceOrigin != EvidenceOrigin.Phone.name)
        assertEquals("Mock glasses", originLabel(EvidenceOrigin.MockWearables))
        assertEquals("Glasses", originLabel(EvidenceOrigin.Glasses))
        assertEquals("Camera", originLabel(EvidenceOrigin.Phone))
        assertEquals("Imported", originLabel(EvidenceOrigin.RecordedFixture))
    }

    @Test
    fun a_photo_keeps_the_mode_it_was_taken_in() {
        val photo = GlassesPhoto(java.io.File("x.jpg"), GlassesMode.Mock)
        assertEquals("MockWearables", photo.origin)
    }

    @Test
    fun phase_follows_registration_before_anything_else() {
        assertEquals(GlassesPhase.Unavailable, glassesPhase(false, RegistrationState.REGISTERED, true, null, StreamState.STREAMING))
        assertEquals(GlassesPhase.Unavailable, glassesPhase(true, null, false, null, null))
        assertEquals(GlassesPhase.Unavailable, glassesPhase(true, RegistrationState.UNAVAILABLE, false, null, null))
        assertEquals(GlassesPhase.NotRegistered, glassesPhase(true, RegistrationState.AVAILABLE, true, null, null))
        assertEquals(GlassesPhase.NotRegistered, glassesPhase(true, RegistrationState.UNREGISTERING, true, null, null))
        assertEquals(GlassesPhase.Registering, glassesPhase(true, RegistrationState.REGISTERING, false, null, null))
    }

    @Test
    fun phase_after_registration_tracks_device_session_and_stream() {
        val r = RegistrationState.REGISTERED
        assertEquals(GlassesPhase.NoGlasses, glassesPhase(true, r, false, null, null))
        assertEquals(GlassesPhase.Connected, glassesPhase(true, r, true, null, null))
        assertEquals(GlassesPhase.Starting, glassesPhase(true, r, true, DeviceSessionState.STARTING, null))
        assertEquals(GlassesPhase.Starting, glassesPhase(true, r, true, DeviceSessionState.STARTED, StreamState.STARTING))
        assertEquals(GlassesPhase.Streaming, glassesPhase(true, r, true, DeviceSessionState.STARTED, StreamState.STREAMING))
        assertEquals(GlassesPhase.Paused, glassesPhase(true, r, true, DeviceSessionState.STARTED, StreamState.PAUSED))
        assertEquals(GlassesPhase.Paused, glassesPhase(true, r, true, DeviceSessionState.PAUSED, null))
        assertEquals(GlassesPhase.Connected, glassesPhase(true, r, true, DeviceSessionState.STARTED, StreamState.STOPPED))
    }

    @Test
    fun streaming_is_only_the_sdk_streaming_state() {
        assertTrue(GlassesState(stream = StreamState.STREAMING).streaming)
        assertFalse(GlassesState(stream = StreamState.STARTED).streaming)
        assertFalse(GlassesState(stream = StreamState.PAUSED).streaming)
    }

    @Test
    fun only_a_short_capture_press_or_a_touchpad_select_is_a_shutter() {
        assertTrue(isShutterPress(InputEvent.Capture(CapturePressType.SHORT_PRESS, InputSource.CAPTURE_BUTTON, 1)))
        assertTrue(isShutterPress(InputEvent.Select(InputSource.CAPTOUCH, 1)))
        assertFalse(isShutterPress(InputEvent.Capture(CapturePressType.HOLD, InputSource.CAPTURE_BUTTON, 1)))
        assertFalse(isShutterPress(InputEvent.Capture(CapturePressType.DOUBLE_PRESS, InputSource.CAPTURE_BUTTON, 1)))
        assertFalse(isShutterPress(InputEvent.Select(InputSource.NEURAL_BAND, 1)))
        assertFalse(isShutterPress(InputEvent.Nav(NavDirection.entries.first(), InputSource.CAPTOUCH, 1)))
    }
}
