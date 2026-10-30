package com.lensqi.fieldops.data

import com.lensqi.fieldops.data.api.ApiOutcome
import com.lensqi.fieldops.data.queue.Disposition
import com.lensqi.fieldops.data.queue.dispositionOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueueDispositionTest {
    @Test
    fun `a transient failure keeps the queued photo for a later retry`() {
        for (outcome in listOf(
            ApiOutcome.Unreachable("no route"),
            ApiOutcome.Rejected(503, "http_503", "overloaded"),
            ApiOutcome.Rejected(504, "model_timeout", "timed out"),
            ApiOutcome.Rejected(429, "rate_limited", "slow down"),
            ApiOutcome.Rejected(409, "provider_unavailable", "Muse is down"),
            ApiOutcome.Rejected(409, "invalid_model_output", "bad JSON"),
        )) assertEquals(Disposition.RetryLater, dispositionOf(outcome), outcome.toString())
    }

    @Test
    fun `only a refusal or a delivery ends the command`() {
        assertEquals(Disposition.Sent, dispositionOf(ApiOutcome.Ok(Unit)))
        assertEquals(Disposition.Refused, dispositionOf(ApiOutcome.Rejected(409, "invalid_state", "step is done")))
        assertEquals(Disposition.Refused, dispositionOf(ApiOutcome.Rejected(400, "bad_image", "not an image")))
        assertEquals(Disposition.Refused, dispositionOf(ApiOutcome.Rejected(0, "client_digest_mismatch", "bytes changed")))
    }
}
