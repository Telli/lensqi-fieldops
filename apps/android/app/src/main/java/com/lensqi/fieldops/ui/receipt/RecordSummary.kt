package com.lensqi.fieldops.ui.receipt

import com.lensqi.fieldops.data.api.DiagnosticSource
import com.lensqi.fieldops.data.api.VerificationOutcomeKind
import com.lensqi.fieldops.data.api.VerificationResult
import com.lensqi.fieldops.ui.common.TechnicianText
import com.lensqi.fieldops.ui.common.plainReason
import kotlinx.serialization.json.*

data class RecordCheck(val passed: Boolean?, val source: DiagnosticSource?, val at: String)
data class RecordSummary(val title: String, val reasons: List<String>, val checks: List<RecordCheck>, val photos: Int, val generated: String)

/** Read-only projection of the existing receipt. Unknown values never become a successful result. */
fun recordSummary(record: JsonObject): RecordSummary {
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    val decision = record["decision"] as? JsonObject
    val result = VerificationResult.entries.firstOrNull { it.name == decision?.text("result") }
    val inspection = record["inspection"] as? JsonObject
    val kind = VerificationOutcomeKind.entries.firstOrNull { it.name == inspection?.text("outcomeKind") }
    // Shown earliest first. The record keeps event order and completion times come from wall clocks, so sort by time;
    // the sort is stable, so checks with no readable time keep their recorded order, after the timed ones.
    val checks = (record["diagnostics"] as? JsonArray).orEmpty().mapNotNull { item ->
        val value = (item as? JsonObject)?.get("result") as? JsonObject ?: return@mapNotNull null
        RecordCheck((value["passed"] as? JsonPrimitive)?.booleanOrNull,
            DiagnosticSource.entries.firstOrNull { it.name == value.text("source") }, value.text("completedAt").orEmpty())
    }.sortedWith(compareBy(nullsLast()) { c: RecordCheck -> runCatching { java.time.OffsetDateTime.parse(c.at).toInstant() }.getOrNull() })
    return RecordSummary(TechnicianText.outcome(result, kind),
        (decision?.get("reasons") as? JsonArray).orEmpty().map { plainReason((it as? JsonPrimitive)?.contentOrNull.orEmpty()) }.distinct(),
        checks, (record["artifacts"] as? JsonArray).orEmpty().count { (it as? JsonObject)?.text("kind") == "photo" },
        record.text("generatedAt").orEmpty())
}

/** Both the app record route and the downloadable document carry the same inspection identifier. */
fun recordInspectionId(url: String): String? = runCatching {
    val parts = java.net.URI(url).path.split('/').filter { it.isNotBlank() }
    when {
        parts.size == 4 && parts[0] == "m" && parts[1] == "i" && parts[3] == "receipt" -> parts[2]
        parts.size == 4 && parts[0] == "v1" && parts[1] == "inspections" && parts[3] in setOf("receipt", "receipt.html") -> parts[2]
        else -> null
    }?.takeIf { it.matches(Regex("I-[a-zA-Z0-9-]+")) }
}.getOrNull()

fun recordTime(value: String): String = runCatching {
    java.time.OffsetDateTime.parse(value).atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT))
}.getOrDefault("Time unavailable")
