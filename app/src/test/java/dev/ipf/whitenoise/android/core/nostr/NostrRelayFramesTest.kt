package dev.ipf.whitenoise.android.core.nostr

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrRelayFramesTest {
    @Test
    fun parseEventForSubscriptionIgnoresWrongSubscriptionAndMalformedFrames() {
        val expected = "dm-update-test"
        val event =
            JSONObject()
                .put("id", "a".repeat(64))
                .put("pubkey", "b".repeat(64))
                .put("created_at", 1L)
                .put("kind", 30063)
                .put("tags", JSONArray())
                .put("content", "")
                .put("sig", "c".repeat(128))

        val matching =
            JSONArray()
                .put("EVENT")
                .put(expected)
                .put(event)
        val wrongSub =
            JSONArray()
                .put("EVENT")
                .put("other-sub")
                .put(event)

        assertEquals("a".repeat(64), NostrRelayFrames.parseEventForSubscription(matching, expected)?.id)
        assertNull(NostrRelayFrames.parseEventForSubscription(wrongSub, expected))
        assertNull(NostrRelayFrames.parseMessage("not-json"))
    }

    @Test
    fun terminalFramesMatchOnlyExpectedSubscription() {
        val expected = "dm-update-test"
        val eose = JSONArray().put("EOSE").put(expected)
        val closed = JSONArray().put("CLOSED").put(expected).put("finished")
        val wrong = JSONArray().put("EOSE").put("other")

        assertTrue(NostrRelayFrames.isTerminalForSubscription(eose, expected))
        assertTrue(NostrRelayFrames.isTerminalForSubscription(closed, expected))
        assertFalse(NostrRelayFrames.isTerminalForSubscription(wrong, expected))
    }
}
