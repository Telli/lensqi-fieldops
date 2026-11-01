package com.lensqi.fieldops.ui

import com.lensqi.fieldops.ui.receipt.*
import com.lensqi.fieldops.ui.common.TechnicianText
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RecordSummaryTest {
    @Test fun `app record and downloadable document resolve the same inspection`() {
        assertEquals("I-abcd", recordInspectionId("http://localhost:5201/m/i/I-abcd/receipt?embed=1&theme=dark"))
        assertEquals("I-abcd", recordInspectionId("http://localhost:5201/v1/inspections/I-abcd/receipt.html"))
        assertNull(recordInspectionId("http://localhost:5201/unrecognized"))
    }
    @Test fun `record summary preserves simulation and never changes original audit data`() {
        val raw = """{"inspection":{"outcomeKind":"VerifiedCompleteSimulated"},"decision":{"result":"Verified","reasons":[]},"artifacts":[{"kind":"photo"}],"diagnostics":[{"result":{"passed":true,"source":"Simulated"}}]}"""
        val record = Json.parseToJsonElement(raw).jsonObject
        val before = record.toString()
        repeat(3) { // Reopening details/summary is a projection only.
            val summary = recordSummary(record)
            assertEquals("Verified · simulated check", summary.title)
            assertEquals(1, summary.photos)
            assertEquals("Simulated", summary.checks.single().source?.name)
            assertEquals("Connection check passed", TechnicianText.connection(summary.checks.single().passed))
        }
        assertEquals(before, record.toString())
    }
    @Test fun `invalid record timestamps do not echo internal text`() {
        assertEquals("Time unavailable", recordTime("internal_error"))
    }
    @Test fun `unknown record states fail closed and do not expose raw reasons`() {
        val record = Json.parseToJsonElement("""{"decision":{"result":"FutureState","reasons":["internal_code=secret"]}}""").jsonObject
        val summary = recordSummary(record)
        assertEquals("Unable to verify", summary.title)
        assertFalse(summary.reasons.single().contains("secret"))
        assertEquals("Unable to verify", recordSummary(JsonObject(emptyMap())).title)
    }
}
