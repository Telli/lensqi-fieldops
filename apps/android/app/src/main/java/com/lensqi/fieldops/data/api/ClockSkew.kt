package com.lensqi.fieldops.data.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Interceptor
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.abs

/**
 * How far this phone's clock is from the server's, read from the `Date` header of every answer. The server refuses a
 * post-repair photo whose device time is more than [TOLERANCE_MS] before the claim, so the camera warns before the shutter
 * rather than after a refused upload. Observed only; nothing here adjusts a timestamp.
 */
object ClockSkew {
    /** The server's tolerance for a photo's device time (InspectionState.PhotoClockSkewTolerance). */
    const val TOLERANCE_MS = 120_000L
    private val mutable = MutableStateFlow<Long?>(null)
    /** Server time minus phone time in milliseconds; null until a server has answered. */
    val offsetMs: StateFlow<Long?> = mutable

    fun record(offsetMs: Long) { mutable.value = offsetMs }
    fun reset() { mutable.value = null }

    /** `Date` is whole seconds, so a skew under one second reads as zero. Null when the header is missing or unreadable. */
    fun offsetOf(dateHeader: String?, now: Instant): Long? = dateHeader?.let {
        try { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now.toEpochMilli() } catch (e: DateTimeParseException) { null }
    }

    /** The warning shown before a post-repair photo, or null while the clocks agree within the server's tolerance. */
    fun warning(offsetMs: Long?): String? {
        if (offsetMs == null || abs(offsetMs) <= TOLERANCE_MS) return null
        val minutes = abs(offsetMs) / 60_000
        val amount = if (minutes >= 1) "$minutes min" else "${abs(offsetMs) / 1000} s"
        return "This phone's clock is $amount ${if (offsetMs > 0) "behind" else "ahead of"} the inspection server. Set the time automatically before the repair photo, or it may not count."
    }

    /** Reads the server's clock from each answer; a request is never changed. */
    fun interceptor(now: () -> Instant = Instant::now) = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        offsetOf(response.header("Date"), now())?.let(::record)
        response
    }
}
