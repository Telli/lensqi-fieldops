package com.lensqi.fieldops.task

import android.speech.SpeechRecognizer
import com.lensqi.fieldops.voice.HandsFreePolicy
import com.lensqi.fieldops.voice.NOTHING_HEARD
import com.lensqi.fieldops.voice.VoiceCommand
import com.lensqi.fieldops.voice.parseCommand
import com.lensqi.fieldops.voice.speechNotice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Hands-free: the microphone opens again by itself only when nothing else holds the floor, and ends when it cannot open. */
class HandsFreeTest {
    private fun listens(on: Boolean = true, supported: Boolean = true, listening: Boolean = false, speaking: Boolean = false, busy: Boolean = false) =
        HandsFreePolicy.shouldListen(on, supported, listening, speaking, busy)

    @Test fun `abort invalidates callbacks even after a new listen starts`() {
        val sessions = com.lensqi.fieldops.voice.ListenSession()
        val aborted = sessions.start()
        sessions.invalidate()
        assertFalse(sessions.active(aborted))
        val current = sessions.start()
        assertFalse(sessions.active(aborted))
        assertTrue(sessions.active(current))
    }

    @Test fun `the microphone opens only while idle, with hands-free on, nothing listening, LensQi silent and no request pending`() {
        assertTrue(listens())
        assertFalse(HandsFreePolicy.shouldListen(true, true, false, false, false, started = false))
        assertFalse(listens(on = false), "push-to-talk never opens the microphone by itself")
        assertFalse(listens(supported = false), "a phone without a recognizer has nothing to open")
        assertFalse(listens(listening = true), "a listen already open is not reopened")
        assertFalse(listens(speaking = true), "a step or answer being read is never taken as the question")
        assertFalse(listens(busy = true), "a question being answered, or an earlier step on screen, holds the microphone")
        // Once the answer has been spoken and the request settled, it opens again.
        assertTrue(listens(speaking = false, busy = false))
    }

    @Test fun `missing on-device language stops hands-free until explicitly reenabled`() {
        for (code in listOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
            val failures = if (HandsFreePolicy.startFailure(code)) 1 else 0
            assertTrue(HandsFreePolicy.shouldStop(false, failures, 0))
        }
    }

    @Test fun `a start failure after switching on ends hands-free, as does a microphone blocked for good`() {
        assertFalse(HandsFreePolicy.shouldStop(settingsNeeded = false, startFailuresNow = 2, startFailuresAtOn = 2), "failures from before the switch do not count")
        assertTrue(HandsFreePolicy.shouldStop(settingsNeeded = false, startFailuresNow = 3, startFailuresAtOn = 2), "one refusal since the switch ends it")
        assertTrue(HandsFreePolicy.shouldStop(settingsNeeded = true, startFailuresNow = 0, startFailuresAtOn = 0), "Android will not ask for the microphone again")
        assertFalse(HandsFreePolicy.shouldStop(settingsNeeded = false, startFailuresNow = 0, startFailuresAtOn = 0))
    }

    @Test fun `silence is not a failure, so hands-free keeps listening through it, and anything else is`() {
        assertFalse(HandsFreePolicy.startFailure(SpeechRecognizer.ERROR_NO_MATCH), "nothing matched: the normal state between questions")
        assertFalse(HandsFreePolicy.startFailure(SpeechRecognizer.ERROR_SPEECH_TIMEOUT), "no speech before the timeout: likewise")
        assertTrue(HandsFreePolicy.startFailure(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertTrue(HandsFreePolicy.startFailure(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertTrue(HandsFreePolicy.startFailure(SpeechRecognizer.ERROR_NETWORK))
        assertTrue(HandsFreePolicy.startFailure(SpeechRecognizer.ERROR_CLIENT))
        assertTrue(HandsFreePolicy.startFailure(SpeechRecognizer.ERROR_AUDIO))
        // A silent cycle still has a line for push-to-talk; hands-free screens drop it by this exact text.
        assertEquals("I didn't catch that.", NOTHING_HEARD)
    }

    @Test fun `while paused only resume and the voice switch are heard, and nothing else is a command or a question`() {
        for (c in VoiceCommand.entries) assertTrue(HandsFreePolicy.pausedCommandAllowed(c, paused = false), "$c runs while not paused")
        assertTrue(HandsFreePolicy.pausedCommandAllowed(null, paused = false), "a question runs while not paused")
        assertTrue(HandsFreePolicy.pausedCommandAllowed(VoiceCommand.Resume, paused = true))
        assertTrue(HandsFreePolicy.pausedCommandAllowed(VoiceCommand.Mute, paused = true))
        assertTrue(HandsFreePolicy.pausedCommandAllowed(VoiceCommand.Unmute, paused = true))
        for (c in listOf(VoiceCommand.Next, VoiceCommand.Back, VoiceCommand.Repeat, VoiceCommand.Check, VoiceCommand.Skip, VoiceCommand.Pause))
            assertFalse(HandsFreePolicy.pausedCommandAllowed(c, paused = true), "$c is dropped while paused")
        // Ambient talk while paused never reaches the server as a question.
        assertFalse(HandsFreePolicy.pausedCommandAllowed(parseCommand("is the filter on the left"), paused = true))
        assertTrue(HandsFreePolicy.pausedCommandAllowed(parseCommand("ok LensQi, resume"), paused = true))
    }

    @Test fun `the speech badge discloses Meta audio processing even for a stale on device flag`() {
        for (onDevice in listOf(true, false)) {
            assertTrue(speechNotice(onDevice).contains("Meta Voice Transcribe"))
            assertTrue(speechNotice(onDevice).contains("through LensQi"))
            assertFalse(speechNotice(onDevice).contains("no audio leaves"))
        }
    }
}
