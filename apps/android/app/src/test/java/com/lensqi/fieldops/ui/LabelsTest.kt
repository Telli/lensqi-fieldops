package com.lensqi.fieldops.ui

import com.lensqi.fieldops.data.api.InspectionStage
import com.lensqi.fieldops.data.api.VerificationOutcomeKind
import com.lensqi.fieldops.data.api.VisualChangeVerdict
import com.lensqi.fieldops.ui.common.CHANGE_CHECK_KIND
import com.lensqi.fieldops.ui.common.EvidenceState
import com.lensqi.fieldops.ui.common.changeText
import com.lensqi.fieldops.ui.common.excludedReason
import com.lensqi.fieldops.ui.common.plainReason
import com.lensqi.fieldops.ui.common.predictionOutcome
import com.lensqi.fieldops.ui.common.probeValue
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

    @Test fun `an excluded fact's reason reads as unverified or not applicable, never as a verdict on the equipment`() {
        assertEquals("Unverified: firmware not known", excludedReason("firmware_unknown"))
        assertEquals("Not applicable: different firmware", excludedReason("firmware_mismatch"))
        assertEquals("Unverified: hardware revision not known", excludedReason("hardware_revision_unknown"))
        assertEquals("Not applicable: different hardware revision", excludedReason("hardware_revision_mismatch"))
        assertEquals("Not applicable: different equipment", excludedReason("manufacturer_mismatch"))
        assertEquals("Not applicable: different equipment", excludedReason("model_mismatch"))
        assertEquals("Conflicting sources; needs review", excludedReason("knowledge_conflict"))
        // A code this client does not know is still shown, readably, never hidden.
        assertEquals("region lock", excludedReason("region_lock"))
    }

    @Test fun `a prediction is badged by the server's score and a missing probe value is a dash`() {
        assertEquals(EvidenceState.Verified to "Prediction confirmed", predictionOutcome("Confirmed"))
        assertEquals(EvidenceState.Failed to "Prediction falsified", predictionOutcome("Falsified"))
        assertEquals(EvidenceState.Unknown to "Couldn't be scored", predictionOutcome("Unscorable"))
        assertEquals(EvidenceState.Hypothesis to "Waiting for the check", predictionOutcome("Pending"))
        assertEquals(predictionOutcome("Pending"), predictionOutcome(null), "nothing registered reads as pending, never as confirmed")
        assertEquals("http ok", probeValue("HttpOk"))
        assertEquals("—", probeValue(null))
        assertEquals("—", probeValue(""))
    }

    @Test fun `a declined view closes with a plain reason that marks nothing as a fault or a repair`() {
        val reason = plainReason("technician declined the requested view")
        assertTrue(reason.startsWith("You declined the requested view"), reason)
        assertEquals("This inspection is closed.", plainReason("The inspection is closed (Inconclusive)"))
        // The other way an inspection closes inconclusive without a claim: the view budget ran out. Same promise, not the generic retry line.
        // The model can close it too; a closed inspection must never read "try again".
        val stopped = plainReason("model proposed StopInconclusive")
        assertTrue(stopped.startsWith("LensQi couldn't settle this"), stopped)
        // Held for its documentation: a new photo alone will not settle it, so it must not read as a photo problem.
        val held = "Required vendor facts are not confirmed for this equipment (ApplicabilityUnverified): LAB-PORT-MAP-01"
        assertTrue(plainReason(held).contains("documentation couldn't be confirmed"), plainReason(held))
        val sentBack = plainReason("The last check sent the claim back for a new photo: $held")
        assertTrue(sentBack.contains("documentation couldn't be confirmed"), sentBack)
        val budget = plainReason("view budget of 3 exhausted")
        assertTrue(budget.contains("as many views as it may") && budget.contains("no fault or repair record"), budget)
    }

    @Test fun `a record that fails its integrity check says so instead of blaming the connection`() {
        val refused = HttpException(Response.error<Any>(409, "{\"code\":\"receipt_invalid\"}".toResponseBody()))
        assertEquals(EXPORT_INTEGRITY_FAILED, exportFailure(refused))
        assertEquals(EXPORT_FAILED, exportFailure(HttpException(Response.error<Any>(503, "".toResponseBody()))))
        assertEquals(EXPORT_FAILED, exportFailure(java.io.IOException("reset")))
    }
}
