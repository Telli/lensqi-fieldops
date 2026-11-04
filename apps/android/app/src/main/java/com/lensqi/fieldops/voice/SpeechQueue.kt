package com.lensqi.fieldops.voice

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Audio for a line from the server. Null, or an exception, means the device's own voice reads it instead. */
interface SpeechSource { suspend fun fetch(text: String): File? }

/** Plays a fetched line. [play] calls `onEnd(true)` when it finished and `onEnd(false)` when it could not play. */
interface SpokenAudio { fun play(audio: File, onEnd: (Boolean) -> Unit); fun stop() }

/** The device's text-to-speech, always available as the fallback. */
interface DeviceVoice { fun say(id: String, text: String, onEnd: () -> Unit); fun stop() }

/**
 * Reads lines one at a time: audio from [source] when it arrives in time, otherwise the device's voice, so the person always
 * hears the line. After a failed fetch the device's voice is used for [retryAfterMs], so a poor connection does not hold up
 * every line in turn. A line stuck for any reason ends after a limit, so it cannot silence the ones after it.
 * Call every method on one thread; the Speaker uses the main thread.
 */
class SpeechQueue(
    private val scope: CoroutineScope,
    private val source: SpeechSource,
    private val audio: SpokenAudio,
    private val device: DeviceVoice,
    private val clock: () -> Long,
    private val onStart: (String) -> Unit,
    private val onEnd: (String) -> Unit,
    private val retryAfterMs: Long = 60_000,
) {
    private class Line(val id: String, val text: String)

    private val waiting = ArrayDeque<Line>()
    private var current: Line? = null
    private var work: Job? = null
    private var limit: Job? = null
    private var retryAt = Long.MIN_VALUE

    /** [flush] drops whatever is playing or waiting; otherwise the line plays after them. */
    fun speak(id: String, text: String, flush: Boolean) {
        if (flush) stop()
        waiting += Line(id, text)
        if (current == null) next()
    }

    fun stop() {
        work?.cancel(); limit?.cancel()
        audio.stop(); device.stop()
        val ended = listOfNotNull(current) + waiting
        current = null; waiting.clear()
        ended.forEach { onEnd(it.id) }
    }

    private fun next() {
        val line = waiting.removeFirstOrNull() ?: return
        current = line
        onStart(line.id)
        limit = scope.launch {
            delay(MAX_LINE_BASE_MS + MAX_MS_PER_CHAR * line.text.length)
            audio.stop(); device.stop()
            finish(line)
        }
        work = scope.launch {
            val file = fetch(line.text)
            if (file == null) readOnDevice(line)
            else audio.play(file) { played -> if (played) finish(line) else readOnDevice(line) }
        }
    }

    private suspend fun fetch(text: String): File? {
        if (clock() < retryAt) return null
        val file = try { source.fetch(text) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (file == null) retryAt = clock() + retryAfterMs
        return file
    }

    private fun readOnDevice(line: Line) {
        if (current === line) device.say(line.id, line.text) { finish(line) }
    }

    private fun finish(line: Line) {
        if (current !== line) return
        limit?.cancel()
        current = null
        onEnd(line.id)
        next()
    }

    private companion object {
        const val MAX_LINE_BASE_MS = 6_000L
        const val MAX_MS_PER_CHAR = 120L
    }
}
