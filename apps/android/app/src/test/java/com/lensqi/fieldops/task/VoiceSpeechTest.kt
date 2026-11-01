package com.lensqi.fieldops.task

import com.lensqi.fieldops.data.api.TaskCaution
import com.lensqi.fieldops.data.api.TaskStep
import com.lensqi.fieldops.ui.common.TechnicianText
import com.lensqi.fieldops.ui.task.VoicePace
import com.lensqi.fieldops.ui.task.stepSpeech
import com.lensqi.fieldops.voice.SpeechOutput
import com.lensqi.fieldops.voice.SpeechTurns
import com.lensqi.fieldops.voice.mute
import com.lensqi.fieldops.voice.unmute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Spoken output: a step, answer or check result is heard in full, nothing plays while the mic listens, commands say they took. */
class VoiceSpeechTest {
    /** Like the device speaker: a forced line keeps it busy until [finish]; disabled or held, nothing plays. */
    private class FakeSpeaker : SpeechOutput {
        val spoken = mutableListOf<String>()
        var stops = 0
        var reading = false
        override var enabled = true
        override var held = false
        override var listening = false
        override val busy get() = held || listening || reading
        override fun speak(text: String, force: Boolean) {
            if (!enabled || held || listening || (!force && reading)) return
            spoken += text; reading = force
        }
        override fun stop() { stops++; reading = false }
        fun finish() { reading = false }
    }

    @Test fun `a forced line is never cut off by a hint, and the engine's end or the time cap frees the speaker`() {
        var now = 0L
        val turns = SpeechTurns({ now }, maxMsPerChar = 10)
        assertTrue(turns.begin("1", "Step 1. Drain the oil.", force = true))
        assertTrue(turns.playing)
        assertFalse(turns.begin("2", "Move closer to the plug.", force = false))
        // A newer forced line (the next step, an answer) replaces it; the old line's end no longer frees the speaker.
        assertTrue(turns.begin("3", "The plug is the bolt under the pan.", force = true))
        turns.finished("1"); assertTrue(turns.playing)
        turns.finished("3"); assertFalse(turns.playing)
        assertTrue(turns.begin("4", "Move closer to the plug.", force = false))
        // If the engine never reports the end, the cap (3 s + 10 ms a character here) frees it.
        turns.begin("5", "x".repeat(100), force = true)
        now = 3999; assertTrue(turns.playing)
        now = 4001; assertFalse(turns.playing)
        turns.begin("6", "Stop if the oil is hot.", force = true); turns.clear(); assertFalse(turns.playing)
    }

    @Test fun `live hints wait while a step is read or the mic listens, then play`() {
        val speaker = FakeSpeaker()
        val pace = VoicePace(speaker)
        speaker.speak("Step 1. Drain the oil. Stop if the oil is hot.", force = true)
        pace.guidance("Move closer to the drain plug.")
        pace.tick(0, allowed = true); pace.tick(2000, allowed = true); pace.tick(9000, allowed = true)
        assertEquals(1, speaker.spoken.size)
        // Once free, the hint settles for 1.5 s like any new hint, which leaves a pause after the step.
        speaker.finish()
        pace.tick(9500, allowed = true); assertEquals(1, speaker.spoken.size)
        pace.tick(11_000, allowed = true)
        assertEquals("Move closer to the drain plug.", speaker.spoken.last())
        speaker.listening = true
        pace.guidance("Hold the phone steady.")
        pace.tick(20_000, allowed = true); pace.tick(30_000, allowed = true)
        assertEquals(2, speaker.spoken.size)
        speaker.listening = false
        pace.tick(30_500, allowed = true); pace.tick(32_000, allowed = true)
        assertEquals("Hold the phone steady.", speaker.spoken.last())
    }

    @Test fun `losing the target cuts off a hint but not a step being read`() {
        val speaker = FakeSpeaker()
        val pace = VoicePace(speaker)
        pace.guidance("Move closer to the drain plug.")
        pace.guidance(TechnicianText.LOST_ADVICE)
        assertEquals(1, speaker.stops)
        pace.guidance("Move closer to the drain plug.")
        speaker.speak("Step 2. Fit the new filter.", force = true)
        pace.guidance(TechnicianText.LOST_ADVICE)
        assertEquals(1, speaker.stops)
        assertTrue(speaker.reading)
    }

    @Test fun `a step is read with its cautions and stop conditions`() {
        val step = TaskStep("s1", "Drain the oil", "Remove the drain plug with a 15 mm socket", handsOn = true,
            cautions = listOf(TaskCaution("burn", "Hot oil can burn.")), stopIf = listOf("the plug thread is stripped", "oil is still hot"))
        assertEquals("Step 2. Drain the oil. Remove the drain plug with a 15 mm socket. Hot oil can burn. Stop if the plug thread is stripped. Stop if oil is still hot.",
            stepSpeech(step, 2))
        assertEquals("Drain the oil. Remove the drain plug with a 15 mm socket. Hot oil can burn. Stop if the plug thread is stripped. Stop if oil is still hot.", stepSpeech(step))
        assertEquals("Look. Check the level.", stepSpeech(TaskStep("s2", "Look", "Check the level.", handsOn = false)))
    }

    @Test fun `mute and unmute say that they took`() {
        val speaker = FakeSpeaker()
        speaker.mute()
        assertEquals("Voice off. Say unmute to turn it back on.", speaker.spoken.single())
        assertFalse(speaker.enabled)
        speaker.speak("Move closer.", force = true)
        assertEquals(1, speaker.spoken.size)
        speaker.unmute()
        assertTrue(speaker.enabled)
        assertEquals("Voice on.", speaker.spoken.last())
    }
}
