package dev.ipf.whitenoise.android.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.DictionaryEntries
import com.code_intelligence.jazzer.junit.DictionaryFile
import com.code_intelligence.jazzer.junit.FuzzTest
import dev.ipf.whitenoise.android.core.nostr.NostrEvent
import dev.ipf.whitenoise.android.core.nostr.NostrEventVerifier
import dev.ipf.whitenoise.android.core.nostr.NostrRelayFrames
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Tag

@Tag("fuzz-zapstore")
class ZapstoreProtocolFuzzTest {
    private val signedReleaseFixture =
        "{\"id\":\"753ec8cfa65fa30e118c1311253deea089efc40e5c008e507194ad17898fd087\",\"pubkey\":\"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798\",\"created_at\":1800000100,\"kind\":30063,\"tags\":[[\"d\",\"org.parres.darkmatter@2026.6.20\"],[\"summary\",\"Dark Matter release\"]],\"content\":\"\",\"sig\":\"4320d14456f14da853d5213bc677ea8e0bb3253dfaca20b46193236709135c4a6c62e46d318a83829a69a4061b0224eb1708c47684d11d3effa1cefa25aa1167\"}"

    @DictionaryEntries(
        "EVENT",
        "EOSE",
        "CLOSED",
        "\"id\"",
        "\"pubkey\"",
        "\"sig\"",
        "dm-update-test",
    )
    @DictionaryFile(resourcePath = "/fuzz-grammar.dict")
    @FuzzTest
    fun fuzzZapstoreProtocol(data: FuzzedDataProvider) {
        when (ZapstoreSubtarget.fromId(data.consumeSubtarget(ZapstoreSubtarget.COUNT))) {
            ZapstoreSubtarget.NostrEventJson -> fuzzNostrEventJson(data)
            ZapstoreSubtarget.RelayEnvelopeFrames -> fuzzRelayEnvelopeFrames(data)
            ZapstoreSubtarget.RelayEnvelopeSequence -> fuzzRelayEnvelopeSequence(data)
        }
    }

    private fun fuzzNostrEventJson(data: FuzzedDataProvider) {
        val jsonText = data.consumeParserInput()
        if (!FuzzJsonStructure.withinBounds(jsonText)) return
        val json = runCatching { JSONObject(jsonText) }.getOrNull() ?: return
        val parsed = NostrEvent.fromJson(json)
        if (parsed == null) return

        assertFixedWidthHex(parsed.id, 64)
        assertFixedWidthHex(parsed.pubkey, 64)
        assertFixedWidthHex(parsed.sig, 128)

        val canonicalOnce = parsed.canonicalJson()
        val canonicalTwice = parsed.canonicalJson()
        FuzzAssertions.assertEquals("canonical JSON is not stable", canonicalOnce, canonicalTwice)

        val reparsed = NostrEvent.fromJson(json)
        if (reparsed != null) {
            FuzzAssertions.assertEquals(
                "reparsed canonical JSON differs",
                canonicalOnce,
                reparsed.canonicalJson(),
            )
        }

        if (NostrEventVerifier.verifies(parsed)) {
            assertMutationsBreakVerification(parsed)
        }
    }

    private fun fuzzRelayEnvelopeFrames(data: FuzzedDataProvider) {
        val directFrame = data.consumeParserInput()
        if (directFrame.isNotEmpty()) {
            exerciseRelayFrame(directFrame, "dm-update-test")
            return
        }

        val expectedSubscription = data.consumeAsciiString(64)
        val wrongSubscription = data.consumeAsciiString(64)
        val frameCount = data.consumeBoundedFrameCount()
        val collected = mutableListOf<NostrEvent>()

        repeat(frameCount) {
            val frameText =
                when (data.consumeInt(0, 5)) {
                    0 -> eventFrame(expectedSubscription, data)
                    1 -> eventFrame(wrongSubscription, data)
                    2 -> terminalFrame("EOSE", expectedSubscription)
                    3 -> terminalFrame("CLOSED", expectedSubscription)
                    4 -> malformedFrame(data)
                    else -> noticeFrame(data)
                }

            val message = NostrRelayFrames.parseMessage(frameText) ?: return@repeat
            NostrRelayFrames.parseEventForSubscription(message, expectedSubscription)?.let { collected += it }
            if (NostrRelayFrames.frameType(message) == "EVENT" &&
                NostrRelayFrames.subscriptionId(message) != expectedSubscription
            ) {
                FuzzAssertions.assertNull(
                    "wrong-subscription EVENT must not parse for expected subscription",
                    NostrRelayFrames.parseEventForSubscription(message, expectedSubscription),
                )
            }
        }

        collected.forEach { event ->
            assertFixedWidthHex(event.id, 64)
            assertFixedWidthHex(event.pubkey, 64)
            assertFixedWidthHex(event.sig, 128)
        }
    }

    private fun fuzzRelayEnvelopeSequence(data: FuzzedDataProvider) {
        val directFrames = data.consumeParserInput()
        if (directFrames.isNotEmpty()) {
            val subscriptionId = "dm-update-test"
            directFrames
                .lineSequence()
                .filter { it.isNotBlank() }
                .take(FuzzBounds.MAX_FRAMES)
                .forEach { frameText -> exerciseRelayFrame(frameText, subscriptionId) }
            return
        }

        val subscriptionId = "dm-update-test"
        val frames = buildFrameSequence(data, subscriptionId)
        var events = 0
        var terminal = false

        frames.forEach { frameText ->
            val message = NostrRelayFrames.parseMessage(frameText) ?: return@forEach
            if (NostrRelayFrames.parseEventForSubscription(message, subscriptionId) != null) {
                events++
            }
            if (NostrRelayFrames.isTerminalForSubscription(message, subscriptionId)) {
                terminal = true
            }
            if (NostrRelayFrames.frameType(message) == "EVENT" &&
                NostrRelayFrames.subscriptionId(message) != subscriptionId
            ) {
                FuzzAssertions.assertNull(
                    "wrong-subscription EVENT must not parse for expected subscription",
                    NostrRelayFrames.parseEventForSubscription(message, subscriptionId),
                )
            }
        }

        if (frames.any { it.contains("EOSE") && it.contains(subscriptionId) }) {
            FuzzAssertions.assertTrue("EOSE frame must mark subscription terminal", terminal)
        }
    }

    private fun exerciseRelayFrame(
        frameText: String,
        expectedSubscription: String,
    ) {
        if (!FuzzJsonStructure.withinBounds(frameText)) return
        val message = NostrRelayFrames.parseMessage(frameText) ?: return
        NostrRelayFrames.parseEventForSubscription(message, expectedSubscription)?.let { event ->
            assertFixedWidthHex(event.id, 64)
            assertFixedWidthHex(event.pubkey, 64)
            assertFixedWidthHex(event.sig, 128)
        }
        if (NostrRelayFrames.frameType(message) == "EVENT" &&
            NostrRelayFrames.subscriptionId(message) != expectedSubscription
        ) {
            FuzzAssertions.assertNull(
                "wrong-subscription EVENT must not parse for expected subscription",
                NostrRelayFrames.parseEventForSubscription(message, expectedSubscription),
            )
        }
        NostrRelayFrames.isTerminalForSubscription(message, expectedSubscription)
    }

    private fun eventFrame(
        subscriptionId: String,
        data: FuzzedDataProvider,
    ): String {
        val eventJson =
            when (data.consumeInt(0, 2)) {
                0 -> signedReleaseFixture
                1 -> minimalEventJson(data)
                else -> data.consumeBoundedUtf8()
            }
        if (!FuzzJsonStructure.withinBounds(eventJson)) {
            return malformedFrame(data)
        }
        val eventObject = runCatching { JSONObject(eventJson) }.getOrNull()
        return JSONArray()
            .put("EVENT")
            .put(subscriptionId)
            .put(eventObject ?: JSONObject())
            .toString()
    }

    private fun terminalFrame(
        type: String,
        subscriptionId: String,
    ): String = JSONArray().put(type).put(subscriptionId).toString()

    private fun noticeFrame(data: FuzzedDataProvider): String =
        JSONArray()
            .put("NOTICE")
            .put(data.consumeBoundedUtf8())
            .toString()

    private fun malformedFrame(data: FuzzedDataProvider): String = data.consumeBoundedUtf8()

    private fun minimalEventJson(data: FuzzedDataProvider): String {
        val id = randomHex(data, 64)
        val pubkey = randomHex(data, 64)
        val sig = randomHex(data, 128)
        return JSONObject()
            .put("id", id)
            .put("pubkey", pubkey)
            .put("created_at", data.consumeLong())
            .put("kind", data.consumeInt())
            .put("tags", JSONArray())
            .put("content", data.consumeBoundedUtf8(1024))
            .put("sig", sig)
            .toString()
    }

    private fun randomHex(
        data: FuzzedDataProvider,
        length: Int,
    ): String =
        buildString(length) {
            repeat(length) {
                append(FuzzGrammar.hexChars[data.consumeInt(0, FuzzGrammar.hexChars.length - 1)])
            }
        }

    private fun buildFrameSequence(
        data: FuzzedDataProvider,
        subscriptionId: String,
    ): List<String> {
        val count = data.consumeBoundedFrameCount()
        return List(count) {
            when (data.consumeInt(0, 4)) {
                0 -> eventFrame(subscriptionId, data)
                1 -> eventFrame("another-$it", data)
                2 -> terminalFrame("EOSE", subscriptionId)
                3 -> terminalFrame("CLOSED", subscriptionId)
                else -> malformedFrame(data)
            }
        }
    }

    private fun assertFixedWidthHex(
        value: String,
        length: Int,
    ) {
        FuzzAssertions.assertEquals("hex field length mismatch", length, value.length)
        FuzzAssertions.assertTrue("hex field contains non-hex characters", value.all { it in '0'..'9' || it in 'a'..'f' })
    }

    private fun assertMutationsBreakVerification(event: NostrEvent) {
        FuzzAssertions.assertFalse(
            "pubkey mutation must break verification",
            NostrEventVerifier.verifies(event.copy(pubkey = flipHexChar(event.pubkey))),
        )
        FuzzAssertions.assertFalse(
            "created_at mutation must break verification",
            NostrEventVerifier.verifies(event.copy(createdAt = event.createdAt + 1)),
        )
        FuzzAssertions.assertFalse(
            "kind mutation must break verification",
            NostrEventVerifier.verifies(event.copy(kind = event.kind + 1)),
        )
        FuzzAssertions.assertFalse(
            "tags mutation must break verification",
            NostrEventVerifier.verifies(
                event.copy(tags = event.tags + listOf(listOf("fuzz", "mutated"))),
            ),
        )
        FuzzAssertions.assertFalse(
            "content mutation must break verification",
            NostrEventVerifier.verifies(event.copy(content = event.content + "x")),
        )
        FuzzAssertions.assertFalse(
            "signature mutation must break verification",
            NostrEventVerifier.verifies(event.copy(sig = flipHexChar(event.sig, atEnd = true))),
        )
    }

    private fun flipHexChar(
        hex: String,
        atEnd: Boolean = false,
    ): String {
        val index = if (atEnd) hex.lastIndex else 0
        val flipped =
            when (hex[index].lowercaseChar()) {
                '0' -> '1'
                'a' -> 'b'
                'f' -> 'e'
                else -> '0'
            }
        return hex.substring(0, index) + flipped + hex.substring(index + 1)
    }
}
