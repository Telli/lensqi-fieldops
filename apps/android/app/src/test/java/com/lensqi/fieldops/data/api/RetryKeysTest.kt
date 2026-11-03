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
