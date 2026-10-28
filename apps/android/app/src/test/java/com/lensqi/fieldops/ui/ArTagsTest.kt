package com.lensqi.fieldops.ui

import com.lensqi.fieldops.data.api.Fixture
import com.lensqi.fieldops.data.api.Hypothesis
import com.lensqi.fieldops.data.api.InvestigateResponse
import com.lensqi.fieldops.data.api.ObservationStatus
import com.lensqi.fieldops.data.api.ProposedNextAction
import com.lensqi.fieldops.data.api.Recordings
import com.lensqi.fieldops.data.api.SegmentResponse
import com.lensqi.fieldops.data.api.VisualAssessment
import com.lensqi.fieldops.data.api.VisualChangeAnalysis
import com.lensqi.fieldops.data.api.VisualChangeVerdict
import com.lensqi.fieldops.di.NetworkModule
import com.lensqi.fieldops.ui.inspection.arTags
import com.lensqi.fieldops.ui.inspection.assessmentForPhoto
import com.lensqi.fieldops.ui.inspection.outlineOrder
import com.lensqi.fieldops.ui.inspection.targetConcept
import com.lensqi.fieldops.ui.theme.Fo
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** AR labels come only from real outlines and the AI's findings; positions are SAM's boxes, never invented. */
class ArTagsTest {
    private val json = NetworkModule.json()
    private val fixture: Fixture = json.decodeFromString(ListSerializer(Fixture.serializer()), Recordings.text("fixtures")).first()
    private val segment = json.decodeFromString(SegmentResponse.serializer(), Recordings.text("segment")).segmentation
    private val assessment = json.decodeFromString(InvestigateResponse.serializer(), Recordings.text("investigate")).assessment

    @Test fun `outline order puts the repair target last`() {
        assertEquals(listOf("network switch", "yellow ethernet cable"), outlineOrder(fixture, null))
        val asksForSwitch = assessment.copy(requestedSegmentationConcept = "network switch")
        assertEquals(listOf("yellow ethernet cable", "network switch"), outlineOrder(fixture, asksForSwitch))
    }

    @Test fun `a tag is pinned to the SAM box and carries the AI finding`() {
        val unclear = assessment.copy(hypotheses = listOf(Hypothesis("yellow_cable_near_end_visible", ObservationStatus.Unknown, "Connector hidden", emptyList())))
        val tags = arTags(fixture, unclear, listOf(segment.copy(concept = "yellow ethernet cable")))
        assertEquals(1, tags.size)
        val t = tags.single()
        assertEquals("Yellow cable near end · not clear", t.label)
        assertEquals(Fo.Warning, t.tone)
        assertEquals("Connector hidden", t.detail)
        val box = segment.boxes.maxBy { it.confidence ?: 0.0 }
        assertEquals(box.left, t.box.left); assertEquals(box.top, t.box.top)
    }

    @Test fun `no outline means no tag`() {
        assertTrue(arTags(fixture, assessment, emptyList()).isEmpty())
        assertTrue(arTags(fixture, assessment, listOf(segment.copy(boxes = emptyList(), masks = emptyList()))).isEmpty())
    }

    @Test fun `after a repair the tag shows the server's change verdict`() {
        val change = VisualChangeAnalysis("claim-1", segment.photoEvidenceId, targetPrompt = "yellow ethernet cable", verdict = VisualChangeVerdict.DuplicateImage, reason = "same picture", thresholdsVersion = "visual-change/1")
        val t = arTags(fixture, null, listOf(segment.copy(concept = "yellow ethernet cable")), change).single()
        assertEquals("Yellow cable near end · no change", t.label)
        assertEquals(Fo.Failure, t.tone)
    }

    @Test fun `foreign photo findings and change verdicts are ignored`() {
        val foreignAssessment = assessment.copy(hypotheses = listOf(Hypothesis("yellow_cable_near_end_visible", ObservationStatus.Observed, "From another photo", listOf("other-photo-id"))))
        assertNull(assessmentForPhoto(foreignAssessment, segment.photoEvidenceId))
        val tagFromForeignAssessment = arTags(fixture, foreignAssessment, listOf(segment.copy(concept = "yellow ethernet cable"))).single()
        assertTrue(tagFromForeignAssessment.label.contains("AI outline"))

        val foreignChange = VisualChangeAnalysis("claim-1", "other-photo-id", targetPrompt = "yellow ethernet cable", verdict = VisualChangeVerdict.Changed, reason = "Moved in other photo", thresholdsVersion = "visual-change/1")
        val tagFromForeignChange = arTags(fixture, null, listOf(segment.copy(concept = "yellow ethernet cable")), foreignChange).single()
        assertEquals(Fo.Primary, tagFromForeignChange.tone)
    }

    @Test fun `the target region follows the AI's request`() {
        assertNull(targetConcept(fixture, assessment.copy(nextAction = ProposedNextAction.RunApprovedProbe)))
        val request = VisualAssessment("LAB-SW-01", listOf(Hypothesis("yellow_cable_near_end_visible", ObservationStatus.Unknown, "hidden", emptyList())),
            ProposedNextAction.RequestView, "move closer", null, "m", "s")
        assertEquals("yellow ethernet cable", targetConcept(fixture, request))
    }
}
