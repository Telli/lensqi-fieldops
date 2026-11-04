package com.lensqi.fieldops.ui.camera

/**
 * The camera view kept for a question, refreshed about once a second. A question asks about what the camera sees now, so
 * this keeps a frame ready whether or not live assist is running. A frame stays in memory on the phone and is replaced by
 * the next one; it only leaves the phone when the person asks something.
 */
class ViewFrames(private val intervalMs: Long = 1_000, private val maxAgeMs: Long = 5_000) {
    @Volatile private var jpeg: ByteArray? = null
    @Volatile private var at = 0L

    /** True when a frame analysed now should be kept. */
    fun due(now: Long): Boolean = jpeg == null || now - at >= intervalMs

    fun store(bytes: ByteArray, now: Long) {
        at = now
        jpeg = bytes
    }

    /** The kept frame, or null when there is none recent enough to still be what the camera sees. */
    fun latest(now: Long): ByteArray? = jpeg?.takeIf { now - at <= maxAgeMs }
}
