package com.lensqi.fieldops.wearables

/** A changing scene must settle before it speaks. Repeated guidance is silent; new guidance cannot chatter. */
class SpokenFeedbackGate {
    private var candidate = ""
    private var since = 0L
    private var spoken = ""
    private var spokeAt = Long.MIN_VALUE / 2
    fun next(text: String, now: Long): String? {
        if (candidate != text) { candidate = text; since = now }
        if (text.isBlank() || text == spoken || now - since < 1500 || now - spokeAt < 8000) return null
        spoken = text; spokeAt = now
        return text
    }
}
