package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.MarkdownAutolinkKindFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrEventReferenceTest {
    @Test
    fun decodesNoteNeventAndNaddrThroughOneStrictPath() {
        val id = List(32) { it }
        val author = List(32) { 0xa0 + it }
        val note = encode("note", id)
        val nevent =
            encode(
                "nevent",
                tlv(1, "wss://ignored.example".bytes()) +
                    tlv(0, id) +
                    tlv(2, author) +
                    tlv(3, uintBytes(1u)) +
                    tlv(9, listOf(7, 8)),
            )
        val naddr =
            encode(
                "naddr",
                tlv(0, "article-id".bytes()) +
                    tlv(1, "wss://ignored.example".bytes()) +
                    tlv(2, author) +
                    tlv(3, uintBytes(30_023u)),
            )

        val expectedId = id.hex()
        val expectedAuthor = author.hex()
        assertEquals(NostrEventReference.Event(expectedId), NostrProfileReference.eventReference(note))
        assertEquals(
            NostrEventReference.Event(
                expectedId,
                expectedAuthor,
                1u,
                relayHints = listOf("wss://ignored.example"),
            ),
            NostrProfileReference.eventReference(nevent),
        )
        assertEquals(
            NostrEventReference.Address(
                30_023u,
                expectedAuthor,
                "article-id",
                relayHints = listOf("wss://ignored.example"),
            ),
            NostrProfileReference.eventReference(naddr),
        )
    }

    @Test
    fun rejectsPrivateProfileOversizeMalformedAndConflictingPointers() {
        val id = List(32) { 0x42 }
        val author = List(32) { 0x24 }
        val missingId = encode("nevent", tlv(1, "wss://ignored.example".bytes()))
        val duplicateId = encode("nevent", tlv(0, id) + tlv(0, id))
        val duplicateAuthor = encode("nevent", tlv(0, id) + tlv(2, author) + tlv(2, author))
        val missingCoordinate = encode("naddr", tlv(0, "slug".bytes()) + tlv(2, author))
        val controlCoordinate =
            encode(
                "naddr",
                tlv(0, "bad\u0000slug".bytes()) + tlv(2, author) + tlv(3, uintBytes(1u)),
            )
        val unsupportedKind = encode("nevent", tlv(0, id) + tlv(3, uintBytes(UInt.MAX_VALUE)))
        val truncated = encode("nevent", listOf(0, 32, 1, 2, 3))
        val validOversized =
            encode(
                "nevent",
                tlv(0, id) + List(20) { index -> tlv(9, List(255) { index }) }.flatten(),
            )

        assertNull(NostrProfileReference.eventReference(encode("nsec", id)))
        assertNull(NostrProfileReference.eventReference(encode("npub", id)))
        assertNull(NostrProfileReference.eventReference(missingId))
        assertNull(NostrProfileReference.eventReference(duplicateId))
        assertNull(NostrProfileReference.eventReference(duplicateAuthor))
        assertNull(NostrProfileReference.eventReference(missingCoordinate))
        assertNull(NostrProfileReference.eventReference(controlCoordinate))
        assertNull(NostrProfileReference.eventReference(unsupportedKind))
        assertNull(NostrProfileReference.eventReference(truncated))
        assertTrue(validOversized.length > 5_000)
        assertNull(NostrProfileReference.eventReference(validOversized))
    }

    @Test
    fun documentWalkFindsTypedAndHttpsReferencesAndDeduplicatesThem() {
        val note = encode("note", List(32) { 1 })
        val nevent = encode("nevent", tlv(0, List(32) { 2 }))
        val fourth = encode("note", List(32) { 4 })
        val naddr =
            encode(
                "naddr",
                tlv(0, "entry".bytes()) + tlv(2, List(32) { 3 }) + tlv(3, uintBytes(30_023u)),
            )
        val document =
            MarkdownDocumentFfi(
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            listOf(
                                MarkdownInlineFfi.NostrUri(
                                    MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NOTE, note),
                                ),
                                MarkdownInlineFfi.Link(
                                    dest = "https://example.com/p/$nevent?ref=$note",
                                    title = null,
                                    children = listOf(MarkdownInlineFfi.Text("event")),
                                    classification = MarkdownLinkDestinationKindFfi.WEB,
                                ),
                                MarkdownInlineFfi.Autolink(
                                    url = "https://example.com/?pointer=$naddr",
                                    kind = MarkdownAutolinkKindFfi.URI,
                                    classification = MarkdownLinkDestinationKindFfi.WEB,
                                ),
                                MarkdownInlineFfi.NostrMention(
                                    MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NOTE, fourth),
                                ),
                            ),
                        ),
                        MarkdownBlockFfi.CodeBlock(
                            kind = dev.ipf.marmotkit.MarkdownCodeBlockKindFfi.FENCED,
                            info = "",
                            content = nevent,
                        ),
                    ),
                truncated = false,
                blankLinesBefore = byteArrayOf(0, 0),
            )

        assertEquals(
            listOf(
                "event:${List(32) { 1 }.hex()}",
                "event:${List(32) { 2 }.hex()}",
                "address:30023:${List(32) { 3 }.hex()}:entry",
            ),
            nostrEventReferences(document).map { it.reference.stableId },
        )
    }

    @Test
    fun documentWalkDoesNotScanCodeBlocks() {
        val nevent = encode("nevent", tlv(0, List(32) { 5 }))
        val document =
            MarkdownDocumentFfi(
                blocks =
                    listOf(
                        MarkdownBlockFfi.CodeBlock(
                            kind = dev.ipf.marmotkit.MarkdownCodeBlockKindFfi.FENCED,
                            info = "",
                            content = nevent,
                        ),
                    ),
                truncated = false,
                blankLinesBefore = byteArrayOf(0),
            )

        assertEquals(emptyList<NostrEventReferenceOccurrence>(), nostrEventReferences(document))
    }

    @Test
    fun standaloneEventReferencesCanMoveIntoCardsWithoutHidingProse() {
        val nevent = "nevent1qqspreviewreference"
        val references =
            listOf(
                NostrEventReferenceOccurrence(
                    reference = NostrEventReference.Event("a".repeat(64)),
                    authoredReference = nevent,
                ),
            )

        assertTrue(messageContainsOnlyNostrEventReferences(nevent, references))
        assertTrue(messageContainsOnlyNostrEventReferences("nostr:$nevent", references))
        assertTrue(messageContainsOnlyNostrEventReferences("NOSTR:$nevent", references))
        assertFalse(messageContainsOnlyNostrEventReferences("Read $nevent", references))
        assertFalse(messageContainsOnlyNostrEventReferences("[$nevent](https://example.com)", references))
    }

    private fun tlv(
        type: Int,
        value: List<Int>,
    ): List<Int> = listOf(type, value.size) + value

    private fun String.bytes(): List<Int> = encodeToByteArray().map { it.toInt() and 0xff }

    private fun uintBytes(value: UInt): List<Int> =
        listOf(
            (value shr 24).toInt() and 0xff,
            (value shr 16).toInt() and 0xff,
            (value shr 8).toInt() and 0xff,
            value.toInt() and 0xff,
        )

    private fun List<Int>.hex(): String = joinToString("") { "%02x".format(it) }

    private fun encode(
        hrp: String,
        bytes: List<Int>,
    ): String {
        val data = convertBits(bytes, fromBits = 8, toBits = 5, pad = true)
        val checksum = createChecksum(hrp, data)
        return hrp + "1" + (data + checksum).joinToString("") { BECH32_CHARSET[it].toString() }
    }

    private fun createChecksum(
        hrp: String,
        data: List<Int>,
    ): List<Int> {
        val values = hrpExpand(hrp) + data + List(6) { 0 }
        val polymod = bech32Polymod(values) xor 1
        return (0 until 6).map { i -> (polymod ushr (5 * (5 - i))) and 31 }
    }

    private fun hrpExpand(hrp: String): List<Int> = hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 }

    private fun bech32Polymod(values: List<Int>): Int {
        var checksum = 1
        for (value in values) {
            val top = checksum ushr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            for (i in BECH32_GENERATORS.indices) {
                if (((top ushr i) and 1) != 0) checksum = checksum xor BECH32_GENERATORS[i]
            }
        }
        return checksum
    }

    private fun convertBits(
        values: List<Int>,
        fromBits: Int,
        toBits: Int,
        pad: Boolean,
    ): List<Int> {
        var accumulator = 0
        var bits = 0
        val maxValue = (1 shl toBits) - 1
        val maxAccumulator = (1 shl (fromBits + toBits - 1)) - 1
        val result = mutableListOf<Int>()
        for (value in values) {
            accumulator = ((accumulator shl fromBits) or value) and maxAccumulator
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result += (accumulator ushr bits) and maxValue
            }
        }
        if (pad && bits > 0) result += (accumulator shl (toBits - bits)) and maxValue
        return result
    }

    private companion object {
        const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        val BECH32_GENERATORS = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    }
}
