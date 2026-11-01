package com.lensqi.fieldops.task

import com.lensqi.fieldops.data.api.TaskCaution
import com.lensqi.fieldops.data.api.TaskStep
import com.lensqi.fieldops.ui.common.TechnicianText
import com.lensqi.fieldops.ui.task.VoicePace
import com.lensqi.fieldops.ui.task.stepSpeech
import com.lensqi.fieldops.voice.ListeningHold
import com.lensqi.fieldops.voice.Play
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
            set(value) { field = value; if (value) stop() }
        override val busy get() = held || listening || reading
        override fun speak(text: String, force: Boolean, replace: Boolean) {
            if (!enabled || held || listening || (!force && reading)) return
            spoken += text; reading = force
        }
        override fun stop() { stops++; reading = false }
        fun finish() { reading = false }
    }

    @Test fun `a forced line is never cut off by a hint or by another forced line, unless that one replaces it`() {
        var now = 0L
        val turns = SpeechTurns({ now }, maxMsPerChar = 10)
        assertEquals(Play.Now, turns.begin("1", "Step 1. Drain the oil. Stop if the oil is hot.", force = true))
        assertTrue(turns.playing)
        assertEquals(Play.Drop, turns.begin("2", "Move closer to the plug.", force = false))
        // A check result arriving during the step plays after it, so the stop conditions are heard.
        assertEquals(Play.After, turns.begin("3", "Looks right. The plug is out.", force = true))
        turns.finished("1"); assertTrue(turns.playing)
        turns.finished("3"); assertFalse(turns.playing)
        assertEquals(Play.Now, turns.begin("4", "Move closer to the plug.", force = false))
        // A new step replaces what is still being read about the old one.
        turns.begin("5", "Step 1. Drain the oil.", force = true)
        assertEquals(Play.Now, turns.begin("6", "Step 2. Fit the filter.", force = true, replace = true))
        turns.finished("5"); assertTrue(turns.playing)
        turns.finished("6"); assertFalse(turns.playing)
        // If the engine never reports the end, the cap (3 s + 10 ms a character here, summed over queued lines) frees it.
        now = 10_000
        turns.begin("7", "x".repeat(100), force = true); turns.begin("8", "y".repeat(100), force = true)
        now = 17_999; assertTrue(turns.playing)
        now = 18_001; assertFalse(turns.playing)
        turns.begin("9", "Stop if the oil is hot.", force = true); turns.clear(); assertFalse(turns.playing)
    }

    @Test fun `forced lines cut off or arriving while the mic listens are read afterwards, in order`() {
        val turns = SpeechTurns({ 0L })
        turns.begin("1", "Step 1. Drain the oil. Stop if the oil is hot.", force = true)
        turns.begin("2", "Looks right.", force = true)
        // The mic opens mid-step: both lines wait, the step from its start.
        turns.hold()
        assertFalse(turns.playing)
        turns.hold("I didn't catch that.", replace = false)
        assertEquals(listOf("Step 1. Drain the oil. Stop if the oil is hot.", "Looks right.", "I didn't catch that."), turns.release())
        assertTrue(turns.release().isEmpty())
        // The step changes while listening: the old step's lines are out of date, the new step is read.
        turns.hold("Step 1. Drain the oil.", replace = true)
        turns.hold("Looks right.", replace = false)
        turns.hold("Step 2. Fit the filter. Stop if the seal is torn.", replace = true)
        assertEquals(listOf("Step 2. Fit the filter. Stop if the seal is torn."), turns.release())
        // Speech stopped (the screen closed): nothing waiting is read later on another screen.
        turns.hold("Step 3. Refill the oil.", replace = true); turns.clear()
        assertTrue(turns.release().isEmpty())
        // Only lines still playing wait: one that finished isn't read again.
        turns.begin("3", "Step 2. Fit the filter.", force = true); turns.finished("3")
        turns.hold()
        assertTrue(turns.release().isEmpty())
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

    @Test fun `a hint that settled before a step was read settles again once the step ends`() {
        val speaker = FakeSpeaker()
        val pace = VoicePace(speaker)
        pace.guidance("Move closer to the drain plug.")
        pace.tick(0, allowed = true)
        speaker.speak("Step 1. Drain the oil.", force = true)
        pace.tick(5000, allowed = true)
        speaker.finish()
        pace.tick(9000, allowed = true); assertEquals(1, speaker.spoken.size)
        pace.tick(10_500, allowed = true)
        assertEquals("Move closer to the drain plug.", speaker.spoken.last())
    }

    @Test fun `the mic's hold is released before a command runs, so its spoken reply is heard`() {
        val speaker = FakeSpeaker()
        speaker.speak("Step 1. Drain the oil.", force = true)
        val hold = ListeningHold(speaker)
        hold.started()
        assertTrue(speaker.listening); assertEquals(1, speaker.stops)
        speaker.speak("Move closer.", force = true); assertEquals(1, speaker.spoken.size)
        hold.result("unmute") { speaker.unmute() }
        assertFalse(speaker.listening)
        assertEquals("Voice on.", speaker.spoken.last())
        // Nothing heard: the hold is still released and nothing is delivered.
        hold.started(); hold.result(" ") { error("delivered a blank result") }
        assertFalse(speaker.listening)
        hold.started(); hold.ended(); assertFalse(speaker.listening)
        // Closing the screen mid-listen stops speech before the hold is released, so held lines are dropped, not read.
        hold.started(); val stopsBefore = speaker.stops
        hold.cancel()
        assertFalse(speaker.listening); assertEquals(stopsBefore + 1, speaker.stops)
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
