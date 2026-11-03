package com.lensqi.fieldops.ui

import com.lensqi.fieldops.data.api.EvidenceOrigin
import com.lensqi.fieldops.data.api.InspectionStage
import com.lensqi.fieldops.data.api.InspectionView
import com.lensqi.fieldops.data.api.PhotoEvidence
import com.lensqi.fieldops.data.api.VisualChangeAnalysis
import com.lensqi.fieldops.data.api.VisualChangeVerdict
import com.lensqi.fieldops.ui.common.aiSummary
import com.lensqi.fieldops.ui.common.changeText
import com.lensqi.fieldops.ui.common.rejectedAfterAccepted
import com.lensqi.fieldops.ui.inspection.beforePhoto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The server keeps an earlier post-repair photo accepted when a later one is rejected and moves the stage to
 * NeedsEvidence. The one-sentence read must then be about the rejection, not the acceptance.
 */
class AiSummaryTest {
    private val claim = "claim-1"
    private fun photo(id: String, at: String) = PhotoEvidence(id, EvidenceOrigin.Phone, "sha-$id", at)
    private fun analysis(photoId: String, verdict: VisualChangeVerdict, claimId: String = claim) =
        VisualChangeAnalysis(claimId, photoId, referencePhotoEvidenceId = "P1", verdict = verdict, reason = verdict.name, thresholdsVersion = "v1")
    private fun view(stage: InspectionStage, accepted: String?, analyses: List<VisualChangeAnalysis>, photos: List<PhotoEvidence> = listOf(
        photo("P1", "2026-11-03T09:00:00Z"), photo("P2", "2026-11-03T09:20:00Z"), photo("P3", "2026-11-03T09:30:00Z"),
    )) = InspectionView(
        inspectionId = "i-1", fixtureId = "f-1", fixtureRevision = 1, fixtureDigest = "d", assetId = "LAB-SW-01", stage = stage,
        lastSequence = 9, viewRequests = 0, currentClaimId = claim, repairClaimedAt = "2026-11-03T09:10:00Z", technicianAcknowledged = false,
        photos = photos, diagnostics = emptyList(), checklist = emptyList(), changeAnalyses = analyses, acceptedPostRepairPhotoId = accepted,
    )

    @Test fun `a later rejected photo is the news, not the earlier acceptance`() {
        val v = view(InspectionStage.NeedsEvidence, accepted = "P2",
            analyses = listOf(analysis("P2", VisualChangeVerdict.Changed), analysis("P3", VisualChangeVerdict.NoVisualChange)))
        assertEquals("P3", rejectedAfterAccepted(v)?.photoEvidenceId)
        assertEquals(changeText(VisualChangeVerdict.NoVisualChange).detail, aiSummary(v, null))
    }

    @Test fun `a duplicate later photo is reported the same way`() {
        val v = view(InspectionStage.ReadyToVerify, accepted = "P2",
            analyses = listOf(analysis("P2", VisualChangeVerdict.Changed), analysis("P3", VisualChangeVerdict.DuplicateImage)))
        assertEquals("P3", rejectedAfterAccepted(v)?.photoEvidenceId)
        assertEquals(changeText(VisualChangeVerdict.DuplicateImage).detail, aiSummary(v, null))
    }

    @Test fun `an accepted photo with nothing rejected after it reads as accepted`() {
        val v = view(InspectionStage.ReadyToVerify, accepted = "P2", analyses = listOf(analysis("P2", VisualChangeVerdict.Changed)))
        assertNull(rejectedAfterAccepted(v))
        assertEquals("I've compared your repair photo with the earlier one and accepted it.", aiSummary(v, null))
    }

    @Test fun `a rejection older than the accepted photo does not undo the acceptance`() {
        // P2 was rejected first, then P3 accepted: the latest analysis for the claim is the acceptance.
        val v = view(InspectionStage.ReadyToVerify, accepted = "P3",
            analyses = listOf(analysis("P2", VisualChangeVerdict.NoVisualChange), analysis("P3", VisualChangeVerdict.Changed)))
        assertNull(rejectedAfterAccepted(v))
        assertEquals("I've compared your repair photo with the earlier one and accepted it.", aiSummary(v, null))
    }

    @Test fun `another claim's rejection is not this claim's`() {
        val v = view(InspectionStage.ReadyToVerify, accepted = "P2",
            analyses = listOf(analysis("P2", VisualChangeVerdict.Changed), analysis("P3", VisualChangeVerdict.NoVisualChange, claimId = "claim-0")))
        assertNull(rejectedAfterAccepted(v))
    }

    @Test fun `with no accepted photo the latest rejection is still what is said`() {
        val v = view(InspectionStage.ReadyToVerify, accepted = null, analyses = listOf(analysis("P2", VisualChangeVerdict.NoVisualChange)))
        assertEquals(changeText(VisualChangeVerdict.NoVisualChange).detail, aiSummary(v, null))
    }

    @Test fun `the before photo is the last one taken before the claim`() {
        val v = view(InspectionStage.ReadyToVerify, accepted = "P2", analyses = emptyList())
        assertEquals("P1", beforePhoto(v)?.evidenceId)
        // Without a claim time the first photo stands in; with no photos there is none.
        assertEquals("P1", beforePhoto(v.copy(repairClaimedAt = null))?.evidenceId)
        assertNull(beforePhoto(v.copy(photos = emptyList())))
        // Every photo taken after the claim: none of them is a "before" view, whatever the server allowed.
        assertNull(beforePhoto(v.copy(photos = listOf(photo("P2", "2026-11-03T09:20:00Z"), photo("P3", "2026-11-03T09:30:00Z")))))
    }
}
