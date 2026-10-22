package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.marmotkit.DeletionSourceFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT

/** A received chat timeline record with [plaintext] and, when given, its already parsed [contentTokens]. */
internal fun eventCardTimelineRecord(
    messageIdHex: String,
    plaintext: String,
    timelineAt: ULong = 1uL,
    contentTokens: MarkdownDocumentFfi = EMPTY_MARKDOWN_DOCUMENT,
    deleted: Boolean = false,
) = TimelineMessageRecordFfi(
    clientToken = null,
    messageIdHex = messageIdHex,
    sourceMessageIdHex = null,
    direction = "received",
    groupIdHex = "g",
    sender = "s",
    plaintext = plaintext,
    contentTokens = contentTokens,
    kind = 9uL,
    tags = emptyList(),
    timelineAt = timelineAt,
    receivedAt = timelineAt,
    replyToMessageIdHex = null,
    replyPreview = null,
    mediaJson = null,
    media = emptyList(),
    agentTextStreamJson = null,
    poll = null,
    groupSystem = null,
    hasReports = false,
    edit = null,
    reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
    deleted = deleted,
    deletionSource = DeletionSourceFfi.UNKNOWN,
    deletedByMessageIdHex = null,
    invalidationStatus = null,
    sourceEpoch = null,
    retentionSeconds = null,
    retentionExpiresAt = null,
)

/** A one-paragraph Markdown document holding a `nostr:` URI for each of [bech32References]. */
internal fun eventCardDocument(vararg bech32References: String): MarkdownDocumentFfi =
    MarkdownDocumentFfi(
        blocks =
            listOf(
                MarkdownBlockFfi.Paragraph(
                    bech32References.map { reference ->
                        MarkdownInlineFfi.NostrUri(MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NOTE, reference))
                    },
                ),
            ),
        truncated = false,
        blankLinesBefore = byteArrayOf(0),
    )

/** The NIP-19 `note` encoding of a 32-byte event id given as [eventIdHex]. */
internal fun noteBech32(eventIdHex: String): String {
    val bytes = eventIdHex.chunked(2).map { it.toInt(16) }
    val data = convertTo5Bits(bytes)
    val checksum = bech32Checksum("note", data)
    return "note1" + (data + checksum).joinToString("") { BECH32_CHARSET[it].toString() }
}

/** Regroups 8-bit [bytes] into padded 5-bit words, as Bech32 requires. */
private fun convertTo5Bits(bytes: List<Int>): List<Int> {
    var accumulator = 0
    var bits = 0
    val words = mutableListOf<Int>()
    for (byte in bytes) {
        accumulator = (accumulator shl 8) or byte
        bits += 8
        while (bits >= 5) {
            bits -= 5
            words += (accumulator shr bits) and 31
        }
    }
    if (bits > 0) words += (accumulator shl (5 - bits)) and 31
    return words
}

/** The six-word Bech32 checksum of [data] under [hrp]. */
private fun bech32Checksum(
    hrp: String,
    data: List<Int>,
): List<Int> {
    val expanded = hrp.map { it.code shr 5 } + 0 + hrp.map { it.code and 31 }
    val polymod = bech32Polymod(expanded + data + List(6) { 0 }) xor 1
    return List(6) { index -> (polymod shr (5 * (5 - index))) and 31 }
}

/** The Bech32 BCH polynomial remainder over [values]. */
private fun bech32Polymod(values: List<Int>): Int {
    val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    var checksum = 1
    for (value in values) {
        val top = checksum shr 25
        checksum = ((checksum and 0x1ffffff) shl 5) xor value
        generators.forEachIndexed { index, generator ->
            if ((top shr index) and 1 == 1) checksum = checksum xor generator
        }
    }
    return checksum
}

private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
