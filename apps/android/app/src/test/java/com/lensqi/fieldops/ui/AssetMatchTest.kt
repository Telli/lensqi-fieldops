package com.lensqi.fieldops.ui

import com.lensqi.fieldops.data.api.Hypothesis
import com.lensqi.fieldops.data.api.ObservationStatus
import com.lensqi.fieldops.data.api.ProposedNextAction
import com.lensqi.fieldops.data.api.VisualAssessment
import com.lensqi.fieldops.ui.common.AssetMatch
import com.lensqi.fieldops.ui.common.assetMatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The camera's asset pill claims a match only when LensQi saw some of the equipment, not merely because the model named it. */
class AssetMatchTest {
    private fun assessment(assetId: String, vararg statuses: Pair<String, ObservationStatus>) =
        VisualAssessment(assetId, statuses.map { (id, st) -> Hypothesis(id, st, "reason", emptyList()) }, ProposedNextAction.RequestView, modelId = "m", schemaVersion = "1")

    @Test
    fun `seen equipment with the expected id is a match`() =
        assertEquals(AssetMatch.Match, assetMatch(assessment("LAB-SW-01", "switch_chassis_visible" to ObservationStatus.Observed), "LAB-SW-01"))

    @Test
    fun `a photo with no equipment in it is unseen even when the model names the asset`() =
        assertEquals(
            AssetMatch.Unseen,
            assetMatch(assessment("LAB-SW-01", "switch_chassis_visible" to ObservationStatus.Unknown, "yellow_cable_near_end_visible" to ObservationStatus.Unknown), "LAB-SW-01"),
        )

    @Test
    fun `a seen post-repair item alone does not count as seeing the equipment`() =
        assertEquals(AssetMatch.Unseen, assetMatch(assessment("LAB-SW-01", "post_repair_view" to ObservationStatus.Observed), "LAB-SW-01"))

    @Test
    fun `seen equipment with another asset id is a mismatch`() =
        assertEquals(AssetMatch.Mismatch, assetMatch(assessment("LAB-NODE-A", "switch_chassis_visible" to ObservationStatus.Suspected), "LAB-SW-01"))

    @Test
    fun `no assessment yet means no answer`() = assertNull(assetMatch(null, "LAB-SW-01"))
}
