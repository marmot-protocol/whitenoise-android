package dev.ipf.whitenoise.android.updates

import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import dev.ipf.whitenoise.android.fuzz.FuzzSyntheticCorpusReplay
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NostrEventVerifierTest {
    @Test
    fun replaysSyntheticFuzzCorpus() {
        FuzzSyntheticCorpusReplay.replaySuite(FuzzSyntheticCorpusReplay.Suite.NostrEventVerifier)
    }

    @Test
    fun canonicalEventIdUsesNostrSerializationOrder() {
        val event =
            NostrEvent(
                id = "0".repeat(64),
                pubkey = "1".repeat(64),
                createdAt = 123L,
                kind = 32267,
                tags = listOf(listOf("d", "org.parres.darkmatter"), listOf("a", "30063:${"1".repeat(64)}:org.parres.darkmatter@2026.6.20")),
                content = "",
                sig = "0".repeat(128),
            )
        assertEquals(
            "[0,\"${"1".repeat(64)}\",123,32267,[[\"d\",\"org.parres.darkmatter\"],[\"a\",\"30063:${"1".repeat(64)}:org.parres.darkmatter@2026.6.20\"]],\"\"]",
            event.canonicalJson(),
        )
    }

    @Test
    fun canonicalEventJsonDoesNotEscapeForwardSlash() {
        val event =
            NostrEvent(
                id = "0".repeat(64),
                pubkey = "1".repeat(64),
                createdAt = 123L,
                kind = 30063,
                tags = listOf(listOf("d", "org.parres.darkmatter@2026.6.20"), listOf("summary", "release </notes>")),
                content = "body </content>",
                sig = "0".repeat(128),
            )

        assertEquals(
            "[0,\"${"1".repeat(64)}\",123,30063,[[\"d\",\"org.parres.darkmatter@2026.6.20\"],[\"summary\",\"release </notes>\"]],\"body </content>\"]",
            event.canonicalJson(),
        )
    }

    @Test
    fun completeEventSerializationPreservesEscapesUnicodeAndAllSignedFields() {
        val event =
            NostrEvent(
                id = "0".repeat(64),
                pubkey = "1".repeat(64),
                createdAt = Long.MAX_VALUE,
                kind = 30063,
                tags = listOf(listOf("d", "a/b"), listOf("emoji", "雪😀\n\t\"\\")),
                content = "body </content>\u0001",
                sig = "2".repeat(128),
            )
        assertEquals(event, NostrEvent.fromJson(JSONObject(event.toJson())))
        assertEquals(7, JSONObject(event.toJson()).length())
    }

    @Test
    fun parserRejectsMissingFractionalOrNegativeNumericFields() {
        fun eventJson(): JSONObject =
            JSONObject()
                .put("id", "0".repeat(64))
                .put("pubkey", "1".repeat(64))
                .put("created_at", 1L)
                .put("kind", 1)
                .put("tags", JSONArray())
                .put("content", "")
                .put("sig", "2".repeat(128))

        assertNull(NostrEvent.fromJson(eventJson().apply { remove("created_at") }))
        assertNull(NostrEvent.fromJson(eventJson().put("created_at", -1)))
        assertNull(NostrEvent.fromJson(eventJson().put("created_at", 1.5)))
        assertNull(NostrEvent.fromJson(eventJson().apply { remove("kind") }))
        assertNull(NostrEvent.fromJson(eventJson().put("kind", -1)))
        assertNull(NostrEvent.fromJson(eventJson().put("kind", Int.MAX_VALUE.toLong() + 1)))
        assertNull(NostrEvent.fromJson(eventJson().put("tags", JSONArray().put("not-a-tag-array"))))
        assertNull(NostrEvent.fromJson(eventJson().put("tags", JSONArray().put(JSONArray().put("d").put(1)))))
        assertNull(NostrEvent.fromJson(eventJson().put("content", 7)))
    }
}
