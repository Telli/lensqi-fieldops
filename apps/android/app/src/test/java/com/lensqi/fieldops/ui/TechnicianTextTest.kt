package com.lensqi.fieldops.ui

import com.lensqi.fieldops.ui.common.*
import com.lensqi.fieldops.data.settings.DIAGNOSTICS_DEFAULT
import com.lensqi.fieldops.data.diagnostics.DiagnosticHistory
import com.lensqi.fieldops.data.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TechnicianTextTest {
    @Test fun `unrecognized errors never expose payloads or claim work is saved`() {
        val raw = "HTTP 500 token=secret https://private.example delta=.3"
        assertEquals("This action couldn't be completed. Refresh to check its status before trying again.", friendly("new_code", raw))
        assertFalse(friendly("offline", raw).contains("safe"))
        assertTrue(friendly("offline", raw).contains("Reconnect"))
        assertTrue(friendly("unsafe_plan", raw).contains("Stop"))
    }
    @Test fun `known prerequisites explain the actual recovery action`() {
        assertTrue(plainReason("A new post-repair image is required").contains("new photo"))
        assertTrue(plainReason("The post-repair photo p1 is still being compared with the earlier view").contains("Wait"))
        assertTrue(plainReason("Expected-state version mismatch: inspection is bound to a different fixture digest").contains("site administrator"))
        assertEquals("The required live equipment checks passed.", plainReason("Specified live fixture checks passed"))
        assertTrue(plainReason("Qualifying Simulated fixture checks passed; this is not a live measurement").contains("does not verify live"))
    }
    @Test fun `unknown policy reasons remain unresolved`() {
        assertEquals("A required check is unresolved. Review the evidence and try again.", plainReason("future_code: internal payload"))
        assertEquals("Unable to verify", TechnicianText.outcome(null))
    }
    @Test fun `failed or uncertain verification cannot become success copy`() {
        assertEquals("Needs another check", TechnicianText.outcome(VerificationResult.Denied))
        assertEquals("Unable to verify · review needed", TechnicianText.outcome(VerificationResult.NeedsHumanReview))
        assertEquals("Verified · simulated check", TechnicianText.outcome(VerificationResult.Verified, VerificationOutcomeKind.VerifiedCompleteSimulated))
        assertEquals("Connection check passed", TechnicianText.connection(true))
        assertFalse(TechnicianText.connection(true).contains("fixed", ignoreCase = true))
        assertTrue(changeText(VisualChangeVerdict.DuplicateImage).detail.contains("Take a new photo"))
        assertTrue(changeText(VisualChangeVerdict.Unscorable).detail.contains("not a visual pass"))
    }
    @Test fun `dynamic vocabulary is translated but safety instructions remain`() {
        val text = TechnicianText.guidance("Delta observed; confidence: 0.98. Stop if the cable is hot. Do not remove the cover.")
        assertTrue(text.startsWith("change observed"), text)
        assertFalse(text.contains("0.98"), text)
        assertTrue(text.contains("Stop if the cable is hot. Do not remove the cover."), text)
        assertEquals("Do not remove the grounding wire.", TechnicianText.guidance("Do not remove the grounding wire."))
        assertEquals("point the camera at the same equipment again", TechnicianText.guidance("reacquire target"))
    }
    @Test fun `camera only describes a selected target when actually tracked`() {
        assertEquals("Equipment in view", TechnicianText.cameraStatus(false, false, false, true))
        assertEquals("Keeping your selected equipment in view", TechnicianText.cameraStatus(true, false, false, true))
        assertEquals("Point the camera back at the equipment", TechnicianText.cameraStatus(true, true, true, true))
        assertEquals("Analysis paused briefly · retrying", TechnicianText.cameraStatus(false, false, true, true))
    }
    @Test fun `tracking loss suppresses old target instruction and recovery restores current advice`() {
        assertFalse(TechnicianText.cameraAdvice("Remove the plug", true).contains("Remove"))
        assertTrue(TechnicianText.cameraAdvice("Remove the plug", true).contains("same equipment"))
        assertEquals("Keep the label visible", TechnicianText.cameraAdvice("Keep the label visible", false))
    }
    @Test fun `diagnostics default off and history never retains raw error payloads`() {
        assertFalse(DIAGNOSTICS_DEFAULT)
    }
    @Test fun `structured diagnostic history is bounded and rejects payloads`() {
        DiagnosticHistory.clear()
        repeat(50) { DiagnosticHistory.record("api", "provider_unavailable", 503) }
        assertEquals(40, DiagnosticHistory.entries.value.size)
        DiagnosticHistory.record("https://secret", "token=secret")
        assertEquals("area=operation code=unrecognized_error", DiagnosticHistory.entries.value.first())
        DiagnosticHistory.clear()
        assertTrue(DiagnosticHistory.entries.value.isEmpty())
    }
}
