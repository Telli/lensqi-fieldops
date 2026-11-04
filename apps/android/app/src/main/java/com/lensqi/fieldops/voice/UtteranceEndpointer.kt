package com.lensqi.fieldops.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Local energy detector bounds each utterance; recognition is always performed by Meta. */
class UtteranceEndpointer(private val sampleRate: Int = 24_000) {
    var spoken = false
        private set
    private var bytes = 0
    private var lastSpeech = 0
    fun accept(buffer: ByteArray, count: Int): Boolean {
        if (count <= 0) return false
        bytes += count
        val pcm = ByteBuffer.wrap(buffer, 0, count).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var squares = 0.0
        while (pcm.hasRemaining()) { val x = pcm.get().toDouble() / 32768; squares += x * x }
        if (kotlin.math.sqrt(squares / (count / 2)) > 0.015) { spoken = true; lastSpeech = bytes }
        return bytes >= sampleRate * 2 * 60 ||
            (spoken && bytes - lastSpeech >= sampleRate * 2 * 1.2) ||
            (!spoken && bytes >= sampleRate * 2 * 8)
    }
}
