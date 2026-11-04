package com.lensqi.fieldops.task

import com.lensqi.fieldops.voice.UtteranceEndpointer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UtteranceEndpointerTest {
    private val quiet = ByteArray(4800)
    private val speech = ByteArray(4800) { if (it % 2 == 0) 0 else 16 }
    @Test fun `silence ends after eight seconds without claiming speech`() {
        val detector = UtteranceEndpointer()
        repeat(79) { assertFalse(detector.accept(quiet, quiet.size)) }
        assertTrue(detector.accept(quiet, quiet.size))
        assertFalse(detector.spoken)
    }
    @Test fun `a short pause preserves an utterance and a longer pause ends it`() {
        val detector = UtteranceEndpointer()
        assertFalse(detector.accept(speech, speech.size))
        repeat(8) { assertFalse(detector.accept(quiet, quiet.size)) }
        assertFalse(detector.accept(speech, speech.size))
        repeat(11) { assertFalse(detector.accept(quiet, quiet.size)) }
        assertTrue(detector.accept(quiet, quiet.size))
        assertTrue(detector.spoken)
    }
    @Test fun `continuous sound cannot exceed sixty seconds`() {
        val detector = UtteranceEndpointer()
        repeat(599) { assertFalse(detector.accept(speech, speech.size)) }
        assertTrue(detector.accept(speech, speech.size))
    }
}
