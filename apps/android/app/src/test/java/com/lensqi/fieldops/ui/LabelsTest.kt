package com.lensqi.fieldops.ui

import com.lensqi.fieldops.data.api.InspectionStage
import com.lensqi.fieldops.data.api.VerificationOutcomeKind
import com.lensqi.fieldops.data.api.VisualChangeVerdict
import com.lensqi.fieldops.ui.common.CHANGE_CHECK_KIND
import com.lensqi.fieldops.ui.common.EvidenceState
import com.lensqi.fieldops.ui.common.changeText
import com.lensqi.fieldops.ui.common.stageLabel
import com.lensqi.fieldops.ui.receipt.EXPORT_FAILED
import com.lensqi.fieldops.ui.receipt.EXPORT_INTEGRITY_FAILED
import com.lensqi.fieldops.ui.receipt.exportFailure
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Response

/** Provenance labels: a simulated result is never shown as a live one, and the server's change check is never called inferred. */
class LabelsTest {
    @Test fun `a simulated verification is labelled simulated wherever the stage is shown`() {
        assertEquals("Verified complete · simulated", stageLabel(InspectionStage.VerifiedComplete, VerificationOutcomeKind.VerifiedCompleteSimulated))
        assertEquals("Verified complete", stageLabel(InspectionStage.VerifiedComplete, VerificationOutcomeKind.VerifiedCompleteLive))
        // Older servers send no outcome kind; the label then says only what is known.
        assertEquals("Verified complete", stageLabel(InspectionStage.VerifiedComplete, null))
        assertEquals(stageLabel(InspectionStage.VerifiedComplete), stageLabel(InspectionStage.VerifiedComplete, null))
    }

    @Test fun `the outcome kind changes nothing before the inspection is verified`() {
        for (stage in InspectionStage.entries.filter { it != InspectionStage.VerifiedComplete })
            assertEquals(stageLabel(stage), stageLabel(stage, VerificationOutcomeKind.VerifiedCompleteSimulated), stage.name)
    }

    @Test fun `the change check is computed by code, so a detected change is not a hypothesis`() {
        assertNotEquals(EvidenceState.Hypothesis, changeText(VisualChangeVerdict.Changed).state)
        assertEquals(EvidenceState.HumanConfirmed, changeText(VisualChangeVerdict.Changed).state)
        // It is still not a verification by itself.
        assertNotEquals(EvidenceState.Verified, changeText(VisualChangeVerdict.Changed).state)
        assertEquals(EvidenceState.Failed, changeText(VisualChangeVerdict.NoVisualChange).state)
        assertEquals(EvidenceState.Failed, changeText(VisualChangeVerdict.DuplicateImage).state)
        assertEquals(EvidenceState.Unknown, changeText(VisualChangeVerdict.Unscorable).state)
    }

    @Test fun `the change check is labelled as compared by the server, not inferred`() {
        assertTrue(CHANGE_CHECK_KIND.contains("compared by the server"))
        assertFalse(CHANGE_CHECK_KIND.lowercase().contains("inferred"))
    }

    @Test fun `a record that fails its integrity check says so instead of blaming the connection`() {
        val refused = HttpException(Response.error<Any>(409, "{\"code\":\"receipt_invalid\"}".toResponseBody()))
        assertEquals(EXPORT_INTEGRITY_FAILED, exportFailure(refused))
        assertEquals(EXPORT_FAILED, exportFailure(HttpException(Response.error<Any>(503, "".toResponseBody()))))
        assertEquals(EXPORT_FAILED, exportFailure(java.io.IOException("reset")))
    }
}
