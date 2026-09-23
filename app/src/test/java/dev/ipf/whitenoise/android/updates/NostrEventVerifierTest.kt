package dev.ipf.whitenoise.android.updates

import dev.ipf.whitenoise.android.core.nostr.BIP340
import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import dev.ipf.whitenoise.android.core.nostr.NostrEventVerifier
import dev.ipf.whitenoise.android.fuzz.FuzzSyntheticCorpusReplay
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class NostrEventVerifierTest {
    @Test
    fun replaysSyntheticFuzzCorpus() {
        FuzzSyntheticCorpusReplay.replaySuite(FuzzSyntheticCorpusReplay.Suite.NostrEventVerifier)
    }

    @Test
    fun matchesOfficialBip340ReferenceVectorsFor32ByteMessages() {
        val vectors =
            checkNotNull(javaClass.getResourceAsStream("/bip340-test-vectors.csv"))
                .bufferedReader()
                .useLines { lines ->
                    lines
                        .filterNot { it.startsWith('#') }
                        .filter(String::isNotBlank)
                        .map { line -> line.split(',').map(String::trim) }
                        .toList()
                }
        assertEquals(15, vectors.size)

        vectors.forEach { fields ->
            assertEquals("malformed vector row", 5, fields.size)
            val index = fields[0]
            val publicKey = fields[1]
            val message = fields[2]
            val signature = fields[3]
            val expected = fields[4]
            assertEquals(
                "BIP-340 vector $index",
                expected == "TRUE",
                BIP340.verify(publicKey, message, signature),
            )
        }
    }

    @Test
    fun rejectsMutatedBip340Signature() {
        assertFalse(
            BIP340.verify(
                publicKeyHex = "F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9",
                messageHex = "0000000000000000000000000000000000000000000000000000000000000000",
                signatureHex =
                    "F907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA8215" +
                        "25F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0",
            ),
        )
    }

    @Test
    fun rejectsMalformedBip340InputsWithoutCallingNativeVerifier() {
        val publicKey = "F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9"
        val message = "00".repeat(32)
        val signature =
            "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA8215" +
                "25F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0"

        assertFalse(BIP340.verify("zz" + publicKey.drop(2), message, signature))
        assertFalse(BIP340.verify("", message, signature))
        assertFalse(BIP340.verify(publicKey.dropLast(1), message, signature))
        assertFalse(BIP340.verify(publicKey.dropLast(2), message, signature))
        assertFalse(BIP340.verify(publicKey, "", signature))
        assertFalse(BIP340.verify(publicKey, message.dropLast(1), signature))
        assertFalse(BIP340.verify(publicKey, message + "00", signature))
        assertFalse(BIP340.verify(publicKey, message, "gg".repeat(64)))
        assertFalse(BIP340.verify(publicKey, message, ""))
        assertFalse(BIP340.verify(publicKey, message, signature.dropLast(1)))
        assertFalse(BIP340.verify(publicKey, message, signature.dropLast(2)))
        assertFalse(BIP340.verify(" $publicKey", message, signature))
    }

    @Test
    fun rejectsMutatedEventIdPayloadPublicKeyAndSignature() {
        val event =
            NostrEvent(
                id = "753ec8cfa65fa30e118c1311253deea089efc40e5c008e507194ad17898fd087",
                pubkey = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
                createdAt = 1_800_000_100L,
                kind = 30_063,
                tags =
                    listOf(
                        listOf("d", "org.parres.darkmatter@2026.6.20"),
                        listOf("summary", "Dark Matter release"),
                    ),
                content = "",
                sig =
                    "4320d14456f14da853d5213bc677ea8e0bb3253dfaca20b46193236709135c4a" +
                        "6c62e46d318a83829a69a4061b0224eb1708c47684d11d3effa1cefa25aa1167",
            )

        assertTrue(NostrEventVerifier.verifies(event))
        assertTrue(
            NostrEventVerifier.verifies(
                event.copy(
                    id = event.id.uppercase(Locale.US),
                    sig = event.sig.uppercase(Locale.US),
                ),
            ),
        )
        assertFalse(NostrEventVerifier.verifies(event.copy(pubkey = event.pubkey.uppercase(Locale.US))))
        assertFalse(NostrEventVerifier.verifies(event.copy(id = "0".repeat(64))))
        assertFalse(NostrEventVerifier.verifies(event.copy(content = "mutated")))
        assertFalse(NostrEventVerifier.verifies(event.copy(pubkey = "0".repeat(64))))
        assertFalse(NostrEventVerifier.verifies(event.copy(sig = "0".repeat(128))))
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
