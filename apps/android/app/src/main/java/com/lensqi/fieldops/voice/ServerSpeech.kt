package com.lensqi.fieldops.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.lensqi.fieldops.data.api.FieldOpsApi
import com.lensqi.fieldops.data.api.SpeakRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Asks the LensQi server for a line's audio. Too slow means null: a person mid-task should not wait for a nicer voice. */
class ServerSpeechSource(private val api: () -> FieldOpsApi, private val dir: File, private val timeoutMs: Long = 3_000) : SpeechSource {
    override suspend fun fetch(text: String): File? = withTimeoutOrNull(timeoutMs) {
        val body = api().speak(SpeakRequest(text))
        withContext(Dispatchers.IO) {
            body.use {
                dir.mkdirs()
                val file = File.createTempFile("line", ".mp3", dir)
                try { file.outputStream().use { out -> it.byteStream().copyTo(out) } }
                catch (e: Exception) { file.delete(); throw e }
                file.takeIf { f -> f.length() > 0 } ?: run { file.delete(); null }
            }
        }
    }

    /** Lines left behind when the process ended mid-fetch. */
    suspend fun clearLeftovers() = withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() }; Unit }
}

/** Plays a fetched line on the media output, where TextToSpeech also speaks, and deletes it afterwards. */
class MediaPlayerAudio : SpokenAudio {
    private var player: MediaPlayer? = null
    private var file: File? = null

    override fun play(audio: File, onEnd: (Boolean) -> Unit) {
        stop()
        file = audio
        val p = MediaPlayer()
        player = p
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setOnPreparedListener { it.start() }
            p.setOnCompletionListener { stop(); onEnd(true) }
            p.setOnErrorListener { _, _, _ -> stop(); onEnd(false); true }
            p.setDataSource(audio.path)
            p.prepareAsync()
        } catch (e: Exception) {
            stop()
            onEnd(false)
        }
    }

    override fun stop() {
        player?.let { p -> p.setOnCompletionListener(null); p.setOnErrorListener(null); p.release() }
        player = null
        file?.delete()
        file = null
    }
}
