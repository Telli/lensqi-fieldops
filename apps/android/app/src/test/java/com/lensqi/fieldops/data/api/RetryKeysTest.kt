package com.lensqi.fieldops.data.api

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** A command keeps its Idempotency-Key while the server has not answered, so a retry replays instead of acting twice. */
class RetryKeysTest {
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
