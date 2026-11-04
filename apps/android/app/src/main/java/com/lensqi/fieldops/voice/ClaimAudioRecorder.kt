package com.lensqi.fieldops.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.SystemClock
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

interface ClaimAudioRecorder {
    suspend fun record(stop: AtomicBoolean): File
}

/** Each invocation owns its microphone and temporary WAV; cancellation releases both in finally. */
class WavClaimAudioRecorder @Inject constructor(@ApplicationContext private val context: Context) : ClaimAudioRecorder {
    override suspend fun record(stop: AtomicBoolean): File {
        var file: File? = null
        try {
            return withContext(Dispatchers.IO) {
                check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    "Microphone permission is required. Allow it in Settings or type the claim."
                }
                val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "Microphone is unavailable. Type the claim or try again." }
                val microphone = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4096))
                try {
                    check(microphone.state == AudioRecord.STATE_INITIALIZED) { "Microphone is unavailable." }
                    file = File.createTempFile("repair-claim-", ".wav", context.cacheDir)
                    RandomAccessFile(file, "rw").use { output ->
                        output.write(ByteArray(44))
                        microphone.startRecording()
                        check(microphone.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone could not start." }
                        val buffer = ByteArray(4096)
                        var bytes = 0
                        val deadline = SystemClock.elapsedRealtime() + 60_000
                        while (!stop.get() && bytes < MAX_AUDIO_BYTES && SystemClock.elapsedRealtime() < deadline) {
                            currentCoroutineContext().ensureActive()
                            val count = microphone.read(buffer, 0, minOf(buffer.size, MAX_AUDIO_BYTES - bytes), AudioRecord.READ_NON_BLOCKING)
                            check(count >= 0) { "Microphone recording failed. Try again or type the claim." }
                            if (count > 0) { output.write(buffer, 0, count); bytes += count } else delay(10)
                        }
                        check(bytes >= SAMPLE_RATE / 5 * 2) { "Recording was too short. Speak for at least a moment and try again." }
                        output.seek(0)
                        output.write(wavHeader(bytes))
                    }
                    requireNotNull(file)
                } finally {
                    runCatching { if (microphone.recordingState == AudioRecord.RECORDSTATE_RECORDING) microphone.stop() }
                    microphone.release()
                }
            }
        } catch (e: Exception) {
            file?.delete()
            throw e
        }
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        const val MAX_AUDIO_BYTES = SAMPLE_RATE * 2 * 60
        fun wavHeader(bytes: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + bytes); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(bytes)
        }.array()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceModule {
    @Binds abstract fun recorder(implementation: WavClaimAudioRecorder): ClaimAudioRecorder
}
