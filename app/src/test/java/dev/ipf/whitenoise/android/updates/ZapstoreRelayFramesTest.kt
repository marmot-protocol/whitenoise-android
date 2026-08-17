package dev.ipf.whitenoise.android.updates

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZapstoreRelayFramesTest {
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

        assertEquals("a".repeat(64), ZapstoreRelayFrames.parseEventForSubscription(matching, expected)?.id)
        assertNull(ZapstoreRelayFrames.parseEventForSubscription(wrongSub, expected))
        assertNull(ZapstoreRelayFrames.parseMessage("not-json"))
    }

    @Test
    fun terminalFramesMatchOnlyExpectedSubscription() {
        val expected = "dm-update-test"
        val eose = JSONArray().put("EOSE").put(expected)
        val closed = JSONArray().put("CLOSED").put(expected).put("finished")
        val wrong = JSONArray().put("EOSE").put("other")

        assertTrue(ZapstoreRelayFrames.isTerminalForSubscription(eose, expected))
        assertTrue(ZapstoreRelayFrames.isTerminalForSubscription(closed, expected))
        assertFalse(ZapstoreRelayFrames.isTerminalForSubscription(wrong, expected))
    }
}
