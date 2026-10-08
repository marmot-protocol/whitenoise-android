package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.marmotkit.MarkdownListItemFfi
import dev.ipf.marmotkit.MarkdownListKindFfi

/**
 * Mirrors the shapes a tester actually reads aloud: a heading, emphasis,
 * inline code, a link, a list, and a quote. Each rendered leaf must still
 * align with the projected speech text for the highlight to survive.
 */
internal fun richPaintDocument() =
    MarkdownDocumentFfi(
        truncated = false,
        blankLinesBefore = byteArrayOf(0, 0, 0, 0),
        blocks =
            listOf(
                MarkdownBlockFfi.Heading(
                    level = 1u,
                    inlines = listOf(MarkdownInlineFfi.Text("Release notes")),
                ),
                MarkdownBlockFfi.Paragraph(
                    inlines =
                        listOf(
                            MarkdownInlineFfi.Text("Important "),
                            MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("bright"))),
                            MarkdownInlineFfi.Text(" details with "),
                            MarkdownInlineFfi.Code("code"),
                            MarkdownInlineFfi.Text(" and "),
                            MarkdownInlineFfi.Link(
                                dest = "https://example.com/docs",
                                title = null,
                                children = listOf(MarkdownInlineFfi.Text("a link")),
                                classification = MarkdownLinkDestinationKindFfi.WEB,
                            ),
                            MarkdownInlineFfi.Text("."),
                        ),
                ),
                MarkdownBlockFfi.ListBlock(
                    kind = MarkdownListKindFfi.Bullet("-"),
                    tight = true,
                    items =
                        listOf(
                            MarkdownListItemFfi(
                                blocks =
                                    listOf(
                                        MarkdownBlockFfi.Paragraph(
                                            inlines = listOf(MarkdownInlineFfi.Text("First item.")),
                                        ),
                                    ),
                                checked = null,
                                blankLinesBefore = byteArrayOf(0),
                            ),
                        ),
                ),
                MarkdownBlockFfi.BlockQuote(
                    blocks =
                        listOf(
                            MarkdownBlockFfi.Paragraph(
                                inlines = listOf(MarkdownInlineFfi.Text("A quoted line.")),
                            ),
                        ),
                    blankLinesBefore = byteArrayOf(0),
                ),
            ),
    )

/** Keeps tokenized and untokenized production-row fixtures on the same document shapes. */
internal fun emptyPaintDocument() =
    MarkdownDocumentFfi(
        truncated = false,
        blankLinesBefore = byteArrayOf(),
        blocks = emptyList(),
    )

internal fun plainPaintDocument(text: String) =
    MarkdownDocumentFfi(
        truncated = false,
        blankLinesBefore = byteArrayOf(),
        blocks =
            listOf(
                MarkdownBlockFfi.Paragraph(
                    inlines = listOf(MarkdownInlineFfi.Text(text)),
                ),
            ),
    )

internal fun speakablePaintRecord(
    messageIdHex: String,
    plaintext: String,
    groupIdHex: String,
    sender: String,
) = AppMessageRecordFfi(
    messageIdHex = messageIdHex,
    direction = "received",
    groupIdHex = groupIdHex,
    sender = sender,
    plaintext = plaintext,
    contentTokens = plainPaintDocument(plaintext),
    kind = 9uL,
    tags = emptyList(),
    sourceEpoch = null,
    retentionSeconds = null,
    retentionExpiresAt = null,
    recordedAt = 1uL,
    receivedAt = 1uL,
)
