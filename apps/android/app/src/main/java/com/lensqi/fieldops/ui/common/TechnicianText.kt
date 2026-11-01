package com.lensqi.fieldops.ui.common

import com.lensqi.fieldops.data.api.VerificationOutcomeKind
import com.lensqi.fieldops.data.api.VerificationResult

/** Presentation only. Never used to decide eligibility, target identity or verification. */
object TechnicianText {
    fun outcome(result: VerificationResult?, kind: VerificationOutcomeKind? = null): String = when (result) {
        VerificationResult.Verified -> if (kind == VerificationOutcomeKind.VerifiedCompleteSimulated) "Verified · simulated check" else "Verified"
        VerificationResult.Denied -> "Needs another check"
        VerificationResult.NeedsHumanReview -> "Unable to verify · review needed"
        null -> "Unable to verify"
    }

    fun connection(passed: Boolean?): String = when (passed) {
        true -> "Connection check passed"
        false -> "Connection check failed"
        null -> "Connection check unavailable"
    }

    /** Keep all task/safety content; translate vocabulary, never truncate cautions or infer success. */
    fun guidance(text: String): String {
        // Numeric model metrics are not instructions. Remove only the metric, before its label is translated below;
        // "low confidence: 0.42" keeps its warning as "uncertain".
        var readable = text.replace(Regex("(?i)\\b(low\\s+)?(?:confidence|fps|tracking updates/s|policy version)\\s*[:=]\\s*[a-zA-Z0-9_.%/-]+")) {
            if (it.groupValues[1].isNotEmpty()) "uncertain" else ""
        }
        listOf(
            "reacquire target" to "point the camera at the same equipment again",
            "endpoint" to "connection", "inference" to "camera assessment",
            "knowledge grounding" to "documentation check", "delta" to "change",
            "low confidence" to "uncertain", "SAM" to "camera assessment", "Muse" to "camera assessment",
            "tracking state" to "camera guidance status", "integrity checks" to "record checks",
        ).forEach { (term, replacement) -> readable = readable.replace(Regex("\\b" + Regex.escape(term) + "\\b", RegexOption.IGNORE_CASE), replacement) }
        return readable.replace(Regex("[ \t]{2,}"), " ").trim(' ', '·', ';', ',').ifBlank { "Review the equipment before continuing." }
    }

    /** Lost tracking or paused analysis replaces the advice: earlier part-specific guidance may no longer apply. */
    fun cameraAdvice(advice: String, lost: Boolean, paused: Boolean = false): String = when {
        lost -> "Point the camera at the same equipment again before continuing."
        paused -> "Guidance will resume shortly. Keep the equipment in view."
        else -> guidance(advice)
    }

    fun cameraStatus(selectedAndTracked: Boolean, lost: Boolean, paused: Boolean, hasObjects: Boolean): String = when {
        lost -> "Point the camera back at the equipment"
        paused -> "Analysis paused briefly · retrying"
        selectedAndTracked -> "Keeping your selected equipment in view"
        hasObjects -> "Equipment in view"
        else -> "Point the camera at the equipment"
    }
}
