package com.lensqi.fieldops.data.api

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** A command keeps its Idempotency-Key while the server has not answered, so a retry replays instead of acting twice. */
class RetryKeysTest {
    @Test fun `a lost response can follow a committed decline and must keep its retry key`() = runTest {
        val keys = RetryKeys()
        var recorded = false
        val key = keys.keyFor("decline")
        val result = keys.command<Unit>("decline") {
            recorded = true
            throw java.io.IOException("response lost")
        }
        org.junit.jupiter.api.Assertions.assertTrue(recorded)
        org.junit.jupiter.api.Assertions.assertTrue(result is ApiOutcome.Unreachable)
        assertEquals(key, keys.keyFor("decline"))
        org.junit.jupiter.api.Assertions.assertFalse(com.lensqi.fieldops.ui.inspection.InspectionViewModel.NEEDS_CONNECTION_DECLINE.contains("Nothing was sent"))
        org.junit.jupiter.api.Assertions.assertFalse(com.lensqi.fieldops.ui.inspection.InspectionViewModel.NEEDS_CONNECTION_ADVISOR.contains("Nothing was sent"))
    }

    @Test fun `the key is kept after an unreachable server, a server error or a failed model call`() {
        val keys = RetryKeys()
        val first = keys.keyFor("verify")
        keys.settle("verify", ApiOutcome.Unreachable("timeout"))
        assertEquals(first, keys.keyFor("verify"))
        keys.settle("verify", ApiOutcome.Rejected(503, "http_503", "busy"))
        assertEquals(first, keys.keyFor("verify"))
        keys.settle("verify", ApiOutcome.Rejected(409, "invalid_model_output", "unreadable"))
        assertEquals(first, keys.keyFor("verify"))
    }

    @Test fun `an answer, accepted or refused, drops the key and another action has its own`() = runTest {
        val keys = RetryKeys()
        val verify = keys.keyFor("verify")
        assertNotEquals(verify, keys.keyFor("acknowledge"))
        keys.settle("verify", ApiOutcome.Ok("decided"))
        assertNotEquals(verify, keys.keyFor("verify"), "a decided verify starts a new attempt next time")
        val claim = keys.keyFor("claim:reseated")
        keys.settle("claim:reseated", ApiOutcome.Rejected(409, "invalid_state", "closed"))
        assertNotEquals(claim, keys.keyFor("claim:reseated"), "a refusal is final; a retry is a new request")
        val seen = mutableListOf<String>()
        val outcome = keys.command("verify") { k -> seen += k; "ok" }
        assertEquals(ApiOutcome.Ok("ok"), outcome)
        assertEquals(1, seen.size)
    }
}

/** Pending keys survive process death through the view model's saved state, so a lost answer is still replayed, not repeated. */
class RetryKeysSavedStateTest {
    @Test fun `a key pending when the process dies is the same key after recreation, and settles away`() {
        val saved = androidx.lifecycle.SavedStateHandle()
        val before = RetryKeys(saved)
        val key = before.keyFor("verify:c1@9")
        before.settle("verify:c1@9", ApiOutcome.Unreachable("timeout"))
        val after = RetryKeys(saved)
        assertEquals(key, after.keyFor("verify:c1@9"), "recreated from saved state, the same request keeps its key")
        after.settle("verify:c1@9", ApiOutcome.Ok("decided"))
        assertNotEquals(key, RetryKeys(saved).keyFor("verify:c1@9"), "a settled key is gone from the saved state too")
    }
}

/** When the state a group of pending actions depended on changes elsewhere, they all become new requests. */
class RetryKeysDropAllTest {
    @Test fun `dropAll clears the matching pending actions and leaves the others`() {
        val keys = RetryKeys()
        val diag = keys.keyFor("diagnostic:D1:p1:@claim=c1"); val other = keys.keyFor("diagnostic:D2:p1:@claim=c1"); val ack = keys.keyFor("acknowledge:p1:@claim=c1")
        keys.dropAll("diagnostic:D1:")
        assertNotEquals(diag, keys.keyFor("diagnostic:D1:p1:@claim=c1"))
        assertEquals(other, keys.keyFor("diagnostic:D2:p1:@claim=c1"))
        assertEquals(ack, keys.keyFor("acknowledge:p1:@claim=c1"))
    }
}
