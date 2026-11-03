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
        // The label is translated after the metric goes, so neither the number nor the warning is lost.
        val low = TechnicianText.guidance("Port label read with low confidence: 0.42, check it by eye")
        assertFalse(low.contains("0.42"), low)
        assertTrue(low.contains("uncertain"), low)
    }
    @Test fun `what the model or the plan wrote is shown and spoken as written`() {
        // Step speech is the text handed to the speaker: no vocabulary pass, so the equipment keeps its name.
        val step = TaskStep("s1", "Cartridge", "Replace the Delta cartridge.", handsOn = true)
        assertEquals("Cartridge. Replace the Delta cartridge.", com.lensqi.fieldops.ui.task.stepSpeech(step))
        // The pass is for status strings, where "delta" is a change score and "SAM" a model, not a product.
        assertEquals("change observed", TechnicianText.guidance("Delta observed"))
        assertEquals("camera assessment ran", TechnicianText.guidance("SAM ran"))
    }
    @Test fun `an already-done step is not told to complete checks, a not-ready verify is`() {
        assertFalse(friendly("invalid_state", "The step is already done").contains("complete the required checks"))
        assertTrue(friendly("verification_not_ready", "").contains("complete the required checks"))
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
        // Paused analysis keeps no earlier part-specific instruction and asks for no repositioning.
        val paused = TechnicianText.cameraAdvice("Remove the plug", lost = false, paused = true)
        assertFalse(paused.contains("Remove"), paused); assertFalse(paused.contains("Point the camera"), paused)
    }
    @Test fun `tracking loss drops the stale action but keeps a safety warning the camera gave`() {
        val lost = TechnicianText.cameraAdvice("Unplug the cable from P2. Stop if the cable is hot.", lost = true)
        assertTrue(lost.startsWith(TechnicianText.LOST_ADVICE), lost)
        assertFalse(lost.contains("Unplug"), lost)
        assertTrue(lost.contains("Stop if the cable is hot."), lost)
        assertEquals(TechnicianText.LOST_ADVICE, TechnicianText.cameraAdvice("Unplug the cable from P2.", lost = true))
    }
    @Test fun `spoken advice leaves out the distance estimate so it stays the same between frames`() {
        assertEquals("Move closer to the switch.", TechnicianText.spoken("Move about 12 cm closer to the switch (estimated 60 cm away)."))
        assertEquals(TechnicianText.spoken("Check the label. Good distance from the switch (estimated 45 cm). Capture when ready."),
            TechnicianText.spoken("Check the label. Good distance from the switch (estimated 46 cm). Capture when ready."))
        assertEquals("Keep the label visible", TechnicianText.spoken("Keep the label visible"))
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
