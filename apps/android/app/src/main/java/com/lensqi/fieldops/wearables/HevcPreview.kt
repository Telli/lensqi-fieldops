package com.lensqi.fieldops.wearables

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

/** Splits an Annex-B byte stream (00 00 01 / 00 00 00 01 start codes) into NAL units without their start codes. */
fun annexBNals(bytes: ByteArray): List<ByteArray> {
    val starts = mutableListOf<Pair<Int, Int>>() // (start code offset, payload offset)
    var i = 0
    while (i + 2 < bytes.size) {
        if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0) {
            if (bytes[i + 2].toInt() == 1) { starts += i to i + 3; i += 3; continue }
            if (i + 3 < bytes.size && bytes[i + 2].toInt() == 0 && bytes[i + 3].toInt() == 1) { starts += i to i + 4; i += 4; continue }
        }
        i++
    }
    return starts.mapIndexed { n, (_, payload) -> bytes.copyOfRange(payload, if (n + 1 < starts.size) starts[n + 1].first else bytes.size) }
}

/**
 * Re-frames [bytes] as clean Annex-B: one 4-byte start code per non-empty NAL. Drops anything before the first start code
 * and empty NALs (the Mock Device Kit's first config frame for a video-clip feed carries a container header in front).
 */
fun cleanAnnexB(bytes: ByteArray): ByteArray =
    annexBNals(bytes).filter { it.isNotEmpty() }.fold(ByteArray(0)) { acc, nal -> acc + byteArrayOf(0, 0, 0, 1) + nal }

/** HEVC NAL type: bits 1..6 of the first header byte. 32 = VPS, 33 = SPS, 34 = PPS. */
fun hevcNalType(nal: ByteArray): Int = if (nal.isEmpty()) -1 else (nal[0].toInt() shr 1) and 0x3F

/** True when every NAL in [bytes] is a VPS/SPS/PPS or SEI (32..34, 39, 40), i.e. there is no picture to decode. */
fun isParameterSetsOnly(bytes: ByteArray): Boolean = annexBNals(bytes).let { nals -> nals.isNotEmpty() && nals.all { hevcNalType(it) in PARAMETER_OR_SEI } }

private val PARAMETER_OR_SEI = setOf(32, 33, 34, 39, 40)

/**
 * Keeps the latest VPS, SPS and PPS seen on the stream. The glasses send the VPS once at stream start, so a decoder created
 * later (the preview surface arrived late, or was recreated) must be primed with the remembered set.
 */
class HevcParameterSets {
    private val sets = sortedMapOf<Int, ByteArray>()

    fun offer(bytes: ByteArray) {
        for (nal in annexBNals(bytes)) {
            val type = hevcNalType(nal)
            if (type in 32..34) sets[type] = nal
        }
    }

    /** VPS + SPS + PPS with start codes, or null until all three have been seen. */
    fun complete(): ByteArray? {
        if (sets.size < 3) return null
        return sets.values.fold(ByteArray(0)) { acc, nal -> acc + START_CODE + nal }
    }

    fun reset() = sets.clear()

    private companion object { val START_CODE = byteArrayOf(0, 0, 0, 1) }
}

/**
 * Decodes the glasses' compressed (HEVC) preview stream straight onto a [Surface] with MediaCodec. The stream is
 * requested compressed because the uncompressed path hands over YUV in the phone decoder's own layout, which this code
 * cannot verify on real hardware; HEVC is a standard bitstream and renders the same on the mock and on glasses.
 */
class HevcPreview {
    private val lock = Any()
    private val params = HevcParameterSets()
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var size = 0 to 0
    private var fed = 0
    private var rendered = 0

    fun setSurface(value: Surface?) = synchronized(lock) {
        if (value !== surface) { stopCodec(); surface = value }
    }

    fun feed(raw: ByteArray, width: Int, height: Int, presentationTimeUs: Long, isCodecConfig: Boolean) = synchronized(lock) {
        val bytes = cleanAnnexB(raw)
        if (bytes.isEmpty()) return@synchronized
        params.offer(bytes)
        if (fed++ == 0) Log.i(TAG, "first frame: ${raw.size} bytes, NAL types ${annexBNals(bytes).map(::hevcNalType)}")
        val target = surface ?: return@synchronized
        try {
            if (codec != null && size != width to height) stopCodec()
            val fresh = codec == null
            if (fresh) {
                val csd = params.complete() ?: return@synchronized
                codec = MediaCodec.createDecoderByType(MIME).apply {
                    val format = MediaFormat.createVideoFormat(MIME, width, height).apply { setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height) }
                    configure(format, target, null, 0)
                    start()
                }
                size = width to height
                queue(csd, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            }
            // A "config" frame can carry the parameter sets and the first picture together (the Mock Device Kit does
            // this for video-clip feeds); only a pure parameter-set frame goes in as codec config.
            val configOnly = isCodecConfig && isParameterSetsOnly(bytes)
            if (configOnly && fresh) return@synchronized
            queue(bytes, presentationTimeUs, if (configOnly) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0)
            drain()
        } catch (e: Exception) {
            Log.w(TAG, "Preview decode failed; restarting on the next frame", e)
            stopCodec()
        }
    }

    fun release() = synchronized(lock) { stopCodec(); params.reset() }

    private fun queue(bytes: ByteArray, ptsUs: Long, flags: Int) {
        val c = codec ?: return
        val index = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) return // decoder busy: drop this frame rather than stall the stream
        val buffer = c.getInputBuffer(index) ?: return
        buffer.clear()
        buffer.put(bytes)
        c.queueInputBuffer(index, 0, bytes.size, ptsUs, flags)
    }

    private fun drain() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = c.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> { c.releaseOutputBuffer(index, true); if (rendered++ == 0) Log.i(TAG, "first preview frame rendered") }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> Unit // format or buffers changed; keep draining
            }
        }
    }

    private fun stopCodec() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        size = 0 to 0
    }

    private companion object {
        const val TAG = "HevcPreview"
        const val MIME = "video/hevc"
        const val INPUT_TIMEOUT_US = 10_000L
    }
}
