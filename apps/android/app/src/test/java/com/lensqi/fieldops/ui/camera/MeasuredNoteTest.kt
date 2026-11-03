package com.lensqi.fieldops.ui.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The measured note for a photo is the last measurement seen before the shutter, kept only while it is fresh. */
class MeasuredNoteTest {
    @Test fun `an empty frame after the shutter keeps the last measurement, and an old one is not pinned to a new photo`() {
        var now = 1_000L
        val note = MeasuredNote(maxAgeMs = 10_000) { now }
        assertNull(note.recent())
        note.seen("""[{"part":"switch","distanceCm":84}]""")
        note.seen(null) // live assist stopped for the shutter: the live objects emptied
        assertEquals("""[{"part":"switch","distanceCm":84}]""", note.recent())
        now += 10_001
        assertNull(note.recent(), "a measurement from another view is not attached")
        note.seen("""[{"part":"switch","distanceCm":60}]""")
        assertEquals("""[{"part":"switch","distanceCm":60}]""", note.recent())
    }

    @Test fun `the note frozen at the shutter survives a long confirmation of the still, and is used once`() {
        var now = 1_000L
        val note = MeasuredNote(maxAgeMs = 10_000) { now }
        note.seen("""[{"part":"switch","distanceCm":84}]""")
        note.freeze()                 // shutter
        note.seen(null); now += 45_000 // live assist off, the technician checks "Is this the switch?" for 45 s
        assertEquals("""[{"part":"switch","distanceCm":84}]""", note.take(), "what was measured at the shutter goes with the photo")
        assertNull(note.take(), "the next photo starts clean")
        note.freeze()
        assertNull(note.take(), "a shutter with nothing fresh freezes nothing")
    }
}
