package com.lensqi.fieldops.task

import com.lensqi.fieldops.voice.DeviceVoice
import com.lensqi.fieldops.voice.SpeechQueue
import com.lensqi.fieldops.voice.SpeechSource
import com.lensqi.fieldops.voice.SpokenAudio
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Server audio when it arrives; otherwise the device's voice, so a line is always heard and never out of date. */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeechQueueTest {
    private class Rig(scope: TestScope, start: Boolean = true) {
        val fetched = mutableListOf<String>()
        val played = mutableListOf<String>()
        val onDevice = mutableListOf<String>()
        val started = mutableListOf<String>()
        val ended = mutableListOf<String>()
        var audioEnd: ((Boolean) -> Unit)? = null
        var deviceEnd: (() -> Unit)? = null
        var audioStops = 0
        var answer: suspend (String) -> File? = { File(it) }
        val queue = SpeechQueue(scope.backgroundScope,
            object : SpeechSource { override suspend fun fetch(text: String): File? { fetched += text; return answer(text) } },
            object : SpokenAudio {
                override fun play(audio: File, onEnd: (Boolean) -> Unit) { played += audio.name; audioEnd = onEnd }
                override fun stop() { audioStops++; audioEnd = null }
            },
            object : DeviceVoice {
                override fun say(id: String, text: String, onEnd: () -> Unit) { onDevice += text; deviceEnd = onEnd }
                override fun stop() { deviceEnd = null }
            },
            clock = { scope.testScheduler.currentTime }, onStart = { started += it }, onEnd = { ended += it })
    }

    @Test fun `server audio plays and the device voice stays silent`() = runTest {
        val r = Rig(this)
        r.queue.speak("1", "Hello", flush = true); runCurrent()
        assertEquals(listOf("Hello"), r.played)
        assertEquals(listOf("1"), r.started)
        assertTrue(r.ended.isEmpty())
        r.audioEnd!!(true)
        assertEquals(listOf("1"), r.ended)
        assertTrue(r.onDevice.isEmpty())
    }

    @Test fun `no audio falls back and later lines skip the server until the retry time`() = runTest {
        val r = Rig(this); r.answer = { null }
        r.queue.speak("1", "One", flush = true); runCurrent()
        assertEquals(listOf("One"), r.onDevice)
        r.deviceEnd!!()
        r.queue.speak("2", "Two", flush = true); runCurrent()
        assertEquals(listOf("One", "Two"), r.onDevice)
        assertEquals(listOf("One"), r.fetched)
        r.deviceEnd!!()
        advanceTimeBy(60_001)
        r.answer = { File(it) }
        r.queue.speak("3", "Three", flush = true); runCurrent()
        assertEquals(listOf("One", "Three"), r.fetched)
        assertEquals(listOf("Three"), r.played)
    }

    @Test fun `a failed fetch falls back`() = runTest {
        val r = Rig(this); r.answer = { error("server down") }
        r.queue.speak("1", "One", flush = true); runCurrent()
        assertEquals(listOf("One"), r.onDevice)
    }

    @Test fun `audio that cannot play is read by the device`() = runTest {
        val r = Rig(this)
        r.queue.speak("1", "One", flush = true); runCurrent()
        r.audioEnd!!(false)
        assertEquals(listOf("One"), r.onDevice)
        assertTrue(r.ended.isEmpty())
        r.deviceEnd!!()
        assertEquals(listOf("1"), r.ended)
    }

    @Test fun `lines that do not flush play in order`() = runTest {
        val r = Rig(this)
        r.queue.speak("1", "One", flush = true)
        r.queue.speak("2", "Two", flush = false); runCurrent()
        assertEquals(listOf("One"), r.played)
        r.audioEnd!!(true); runCurrent()
        assertEquals(listOf("One", "Two"), r.played)
    }

    @Test fun `a flush cancels a fetch in flight so the old line is never played`() = runTest {
        val r = Rig(this)
        val slow = CompletableDeferred<File?>()
        r.answer = { if (it == "Old") slow.await() else File(it) }
        r.queue.speak("1", "Old", flush = true); runCurrent()
        r.queue.speak("2", "New", flush = true); runCurrent()
        slow.complete(File("Old")); runCurrent()
        assertEquals(listOf("New"), r.played)
        assertEquals(listOf("1"), r.ended)
        assertTrue(r.onDevice.isEmpty())
    }

    @Test fun `stop silences everything and ends every line once`() = runTest {
        val r = Rig(this)
        r.queue.speak("1", "One", flush = true)
        r.queue.speak("2", "Two", flush = false); runCurrent()
        assertNotNull(r.audioEnd)
        r.queue.stop(); runCurrent()
        assertEquals(listOf("1", "2"), r.ended)
        assertEquals(listOf("One"), r.played)
        r.queue.stop()
        assertEquals(listOf("1", "2"), r.ended)
    }

    @Test fun `an end reported after a stop does not end the next line`() = runTest {
        val r = Rig(this)
        r.queue.speak("1", "One", flush = true); runCurrent()
        val staleEnd = r.audioEnd!!
        r.queue.speak("2", "Two", flush = true); runCurrent()
        staleEnd(true)
        assertEquals(listOf("1"), r.ended)
    }

    @Test fun `a voice that never reports an end cannot silence the next line`() = runTest {
        val r = Rig(this); r.answer = { null }
        r.queue.speak("1", "One", flush = true)
        r.queue.speak("2", "Two", flush = false); runCurrent()
        assertEquals(listOf("One"), r.onDevice)
        advanceTimeBy(6_000L + 120 * 3 + 1); runCurrent()
        assertEquals(listOf("One", "Two"), r.onDevice)
        assertEquals(listOf("1"), r.ended)
    }
}
