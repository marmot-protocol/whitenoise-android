package dev.ipf.whitenoise.android.ui

import dev.ipf.marmotkit.MarkdownAlignmentFfi
import dev.ipf.marmotkit.MarkdownAutolinkKindFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownCodeBlockKindFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.marmotkit.MarkdownListItemFfi
import dev.ipf.marmotkit.MarkdownListKindFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import dev.ipf.marmotkit.MarkdownTableCellFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownSpeakableTextTest {
    @Test
    fun formattingSyntaxIsRemovedWhileBlockBoundariesStaySpeakable() {
        val document =
            MarkdownDocumentFfi(
                truncated = false,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.Heading(
                            level = 1u,
                            inlines = listOf(MarkdownInlineFfi.Text("Status")),
                        ),
                        MarkdownBlockFfi.Paragraph(
                            inlines =
                                listOf(
                                    MarkdownInlineFfi.Text("This is "),
                                    MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("important"))),
                                    MarkdownInlineFfi.Text(" and "),
                                    MarkdownInlineFfi.Emph(listOf(MarkdownInlineFfi.Text("urgent"))),
                                ),
                        ),
                    ),
            )

        assertEquals(
            "Status. This is important and urgent.",
            markdownDocumentToSpeakableText(document),
        )
    }

    @Test
    fun linksSpeakNaturalLabelsAndImagesSpeakAltTextWithoutAnyUrl() {
        val document =
            MarkdownDocumentFfi(
                truncated = false,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            inlines =
                                listOf(
                                    MarkdownInlineFfi.Text("before "),
                                    MarkdownInlineFfi.Link(
                                        dest = "https://example.com/docs",
                                        title = null,
                                        children = listOf(MarkdownInlineFfi.Text("documentation")),
                                        classification = MarkdownLinkDestinationKindFfi.WEB,
                                    ),
                                    MarkdownInlineFfi.Text(" "),
                                    MarkdownInlineFfi.Link(
                                        dest = "https://example.com",
                                        title = null,
                                        children = listOf(MarkdownInlineFfi.Text("https://example.com")),
                                        classification = MarkdownLinkDestinationKindFfi.WEB,
                                    ),
                                    MarkdownInlineFfi.Text(" "),
                                    MarkdownInlineFfi.Link(
                                        dest = "https://example.com/empty",
                                        title = null,
                                        children = emptyList(),
                                        classification = MarkdownLinkDestinationKindFfi.WEB,
                                    ),
                                    MarkdownInlineFfi.Text(" "),
                                    MarkdownInlineFfi.Autolink(
                                        url = "https://example.com/auto",
                                        kind = MarkdownAutolinkKindFfi.URI,
                                        classification = MarkdownLinkDestinationKindFfi.WEB,
                                    ),
                                    MarkdownInlineFfi.Text(" after "),
                                    MarkdownInlineFfi.Image(
                                        dest = "https://example.com/cat.png",
                                        title = null,
                                        alt = listOf(MarkdownInlineFfi.Text("a cat")),
                                        classification = MarkdownLinkDestinationKindFfi.WEB,
                                    ),
                                    MarkdownInlineFfi.Image(
                                        dest = "https://example.com/empty.png",
                                        title = null,
                                        alt = emptyList(),
                                        classification = MarkdownLinkDestinationKindFfi.WEB,
                                    ),
                                ),
                        ),
                    ),
            )

        assertEquals(
            "before documentation after a cat.",
            markdownDocumentToSpeakableText(document),
        )
    }

    @Test
    fun emailAutolinksSpeakTheAddressVisibleInTheBubble() {
        val document =
            MarkdownDocumentFfi(
                truncated = false,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            listOf(
                                MarkdownInlineFfi.Text("Email me at "),
                                MarkdownInlineFfi.Autolink(
                                    url = "bob@example.com",
                                    kind = MarkdownAutolinkKindFfi.EMAIL,
                                    classification = MarkdownLinkDestinationKindFfi.CONTACT,
                                ),
                            ),
                        ),
                    ),
            )

        assertEquals("Email me at bob@example.com.", markdownDocumentToSpeakableText(document))
    }

    @Test
    fun urlShapedLinkLabelsContributeNoSpeech() {
        val document =
            MarkdownDocumentFfi(
                truncated = false,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            listOf(
                                MarkdownInlineFfi.Link(
                                    dest = "https://example.com/domain-label",
                                    title = null,
                                    children = listOf(MarkdownInlineFfi.Text("example.com/docs")),
                                    classification = MarkdownLinkDestinationKindFfi.WEB,
                                ),
                                MarkdownInlineFfi.Link(
                                    dest = "https://nodejs.org",
                                    title = null,
                                    children = listOf(MarkdownInlineFfi.Text("Node.js")),
                                    classification = MarkdownLinkDestinationKindFfi.WEB,
                                ),
                                MarkdownInlineFfi.Text(" "),
                                MarkdownInlineFfi.Link(
                                    dest = "https://vuejs.org",
                                    title = null,
                                    children = listOf(MarkdownInlineFfi.Text("Vue.js")),
                                    classification = MarkdownLinkDestinationKindFfi.WEB,
                                ),
                            ),
                        ),
                    ),
            )

        assertEquals("Node.js Vue.js.", markdownDocumentToSpeakableText(document))
    }

    @Test
    fun literalTrailingCodePunctuationRemainsSpeakable() {
        val document =
            MarkdownDocumentFfi(
                truncated = false,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.CodeBlock(
                            kind = MarkdownCodeBlockKindFfi.FENCED,
                            info = "kotlin",
                            content = "return value;",
                        ),
                    ),
            )

        assertEquals("return value;", markdownDocumentToSpeakableText(document))
    }

    @Test
    fun quotesListsCodeMathAndTablesKeepVisibleContentWithoutMarkers() {
        fun paragraph(text: String) = MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text(text)))

        val document =
            MarkdownDocumentFfi(
                truncated = false,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.BlockQuote(
                            blocks = listOf(paragraph("quoted")),
                            blankLinesBefore = byteArrayOf(),
                        ),
                        MarkdownBlockFfi.ListBlock(
                            kind = MarkdownListKindFfi.Bullet("-"),
                            tight = true,
                            items =
                                listOf(
                                    MarkdownListItemFfi(
                                        blocks = listOf(paragraph("first item")),
                                        checked = null,
                                        blankLinesBefore = byteArrayOf(),
                                    ),
                                    MarkdownListItemFfi(
                                        blocks = listOf(paragraph("second item")),
                                        checked = true,
                                        blankLinesBefore = byteArrayOf(),
                                    ),
                                ),
                        ),
                        MarkdownBlockFfi.CodeBlock(
                            kind = MarkdownCodeBlockKindFfi.FENCED,
                            info = "kotlin",
                            content = "val marker = \"* #\"\nprintln(marker)\n",
                        ),
                        MarkdownBlockFfi.Table(
                            alignments = listOf(MarkdownAlignmentFfi.NONE, MarkdownAlignmentFfi.NONE),
                            header =
                                listOf(
                                    MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text("h1"))),
                                    MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text("h2"))),
                                ),
                            rows =
                                listOf(
                                    listOf(
                                        MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text("c1"))),
                                        MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text("c2"))),
                                    ),
                                ),
                        ),
                        MarkdownBlockFfi.MathBlock("x * y"),
                    ),
            )

        assertEquals(
            "quoted. first item. second item. val marker = \"* #\" println(marker). h1. h2. c1. c2. x * y.",
            markdownDocumentToSpeakableText(document),
        )
    }

    @Test
    @Suppress("LongMethod") // One mixed AST must prove leaf paths and projection identity together.
    fun projectionUsesStableVisibleLeafCoordinatesAcrossListsCodeTablesAndMentions() {
        val alice = "npub1" + "q".repeat(58)
        val document =
            MarkdownDocumentFfi(
                truncated = false,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.ListBlock(
                            kind = MarkdownListKindFfi.Bullet("-"),
                            tight = true,
                            items =
                                listOf(
                                    MarkdownListItemFfi(
                                        blocks =
                                            listOf(
                                                MarkdownBlockFfi.Paragraph(
                                                    listOf(MarkdownInlineFfi.Text("item")),
                                                ),
                                            ),
                                        checked = null,
                                        blankLinesBefore = byteArrayOf(),
                                    ),
                                ),
                        ),
                        MarkdownBlockFfi.CodeBlock(
                            kind = MarkdownCodeBlockKindFfi.FENCED,
                            info = "",
                            content = "code",
                        ),
                        MarkdownBlockFfi.Table(
                            alignments = listOf(MarkdownAlignmentFfi.NONE),
                            header = listOf(MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text("head")))),
                            rows =
                                listOf(
                                    listOf(
                                        MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text("cell"))),
                                    ),
                                ),
                        ),
                        MarkdownBlockFfi.Paragraph(
                            listOf(
                                MarkdownInlineFfi.NostrMention(
                                    MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, alice),
                                ),
                            ),
                        ),
                    ),
            )

        val projection =
            markdownDocumentToSpeakableProjection(
                document = document,
                mentionDisplayName = { "Alice" },
                isGroupMember = { true },
            )
        val repeated =
            markdownDocumentToSpeakableProjection(
                document = document,
                mentionDisplayName = { "Alice" },
                isGroupMember = { true },
            )
        val renamed =
            markdownDocumentToSpeakableProjection(
                document = document,
                mentionDisplayName = { "Alicia" },
                isGroupMember = { true },
            )

        assertEquals("item. code. head. cell. @Alice.", projection.text)
        assertEquals(
            listOf(
                SpeakableTextProjectionSpan(0, 4, "b0/i0/b0/n0", 0, 4),
                SpeakableTextProjectionSpan(6, 10, "b1/code", 0, 4),
                SpeakableTextProjectionSpan(12, 16, "b2/h0/n0", 0, 4),
                SpeakableTextProjectionSpan(18, 22, "b2/r0/c0/n0", 0, 4),
                SpeakableTextProjectionSpan(24, 30, "b3/n0", 0, 6),
            ),
            projection.spans,
        )
        assertTrue(projection.projectionId.isNotEmpty())
        assertEquals(projection.projectionId, repeated.projectionId)
        assertTrue(projection.projectionId != renamed.projectionId)
    }

    @Test
    fun nostrEntitiesMatchBubbleNamesMembershipPrefixesAndShortening() {
        val alice = "npub1" + "q".repeat(58)
        val bob = "npub1" + "p".repeat(58)
        val unknownMember = "npub1" + "z".repeat(58)
        val unknownNonMember = "npub1" + "x".repeat(58)
        val note = "note1" + "n".repeat(58)

        fun entity(
            hrp: MarkdownNostrHrpFfi,
            bech32: String,
        ) = MarkdownNostrEntityFfi(hrp, bech32)
        val document =
            MarkdownDocumentFfi(
                truncated = false,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            listOf(
                                MarkdownInlineFfi.NostrMention(entity(MarkdownNostrHrpFfi.NPUB, alice)),
                                MarkdownInlineFfi.Text(" "),
                                MarkdownInlineFfi.NostrMention(entity(MarkdownNostrHrpFfi.NPUB, bob)),
                                MarkdownInlineFfi.Text(" "),
                                MarkdownInlineFfi.NostrMention(entity(MarkdownNostrHrpFfi.NPUB, unknownMember)),
                                MarkdownInlineFfi.Text(" "),
                                MarkdownInlineFfi.NostrMention(entity(MarkdownNostrHrpFfi.NPUB, unknownNonMember)),
                                MarkdownInlineFfi.Text(" "),
                                MarkdownInlineFfi.NostrUri(entity(MarkdownNostrHrpFfi.NOTE, note)),
                            ),
                        ),
                    ),
            )

        assertEquals(
            "@Alice Bob @npub1zzzzzzz…zzzzzz npub1xxxxxxx…xxxxxx note1nnnnnnn…nnnnnn.",
            markdownDocumentToSpeakableText(
                document = document,
                mentionDisplayName = {
                    when (it) {
                        alice -> "Alice"
                        bob -> "Bob"
                        else -> null
                    }
                },
                isGroupMember = { it == alice || it == unknownMember },
            ),
        )
    }

    @Test
    fun bareUrlsAreOmittedAndLineBreaksRemainSentenceBoundaries() {
        val document =
            MarkdownDocumentFfi(
                truncated = false,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            listOf(
                                MarkdownInlineFfi.Text("Details: https://example.com/private?q=1"),
                                MarkdownInlineFfi.HardBreak,
                                MarkdownInlineFfi.Text("Keep # and * literal"),
                                MarkdownInlineFfi.SoftBreak,
                                MarkdownInlineFfi.Text("www.example.com"),
                            ),
                        ),
                    ),
            )

        assertEquals(
            "Details. Keep # and * literal.",
            markdownDocumentToSpeakableText(document),
        )
        assertEquals(
            "Details. Keep # and * literal.",
            legacyTextToSpeakableText(
                "Details: https://example.com/private?q=1\nKeep # and * literal\nwww.example.com",
            ),
        )
    }

    @Test
    fun softBreaksRemainAnAudiblePause() {
        val document =
            MarkdownDocumentFfi(
                truncated = false,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            listOf(
                                MarkdownInlineFfi.Text("hello"),
                                MarkdownInlineFfi.SoftBreak,
                                MarkdownInlineFfi.Text("world"),
                            ),
                        ),
                    ),
            )

        assertEquals("hello. world.", markdownDocumentToSpeakableText(document))
    }

    @Test
    fun balancedUrlParenthesesAreOmittedWithoutLeakingPathText() {
        assertEquals(
            "See now.",
            legacyTextToSpeakableText("See https://example.com/path_(detail) now"),
        )
        assertEquals(
            "See now.",
            legacyTextToSpeakableText("See (https://example.com/path) now"),
        )
    }

    @Test
    fun malformedBreadthStopsAtTheGlobalNodeBudget() {
        val npub = "npub1" + "q".repeat(58)
        val mentionParagraph =
            MarkdownBlockFfi.Paragraph(
                listOf(
                    MarkdownInlineFfi.NostrMention(
                        MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, npub),
                    ),
                ),
            )
        val breadth = 600
        val repeatedItem =
            MarkdownListItemFfi(
                blocks = List(breadth) { mentionParagraph },
                checked = null,
                blankLinesBefore = byteArrayOf(),
            )
        val document =
            MarkdownDocumentFfi(
                truncated = true,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.ListBlock(
                            kind = MarkdownListKindFfi.Bullet("-"),
                            tight = true,
                            items = List(breadth) { repeatedItem },
                        ),
                    ),
            )
        var resolverCalls = 0

        markdownDocumentToSpeakableText(
            document = document,
            mentionDisplayName = {
                resolverCalls++
                null
            },
        )

        assertTrue(resolverCalls <= MARKDOWN_SPEAKABLE_MAX_NODES)
    }

    @Test
    fun traversalDoesNotReadSiblingsAfterCharacterBudgetIsExhausted() {
        val oversized =
            MarkdownBlockFfi.Paragraph(
                listOf(MarkdownInlineFfi.Text("a".repeat(MARKDOWN_SPEAKABLE_MAX_LENGTH))),
            )
        val blocks =
            object : AbstractList<MarkdownBlockFfi>() {
                override val size = MARKDOWN_MAX_CONTAINER_SIBLINGS + 1

                override fun get(index: Int): MarkdownBlockFfi {
                    if (index == 0) return oversized
                    error("read a sibling after the traversal budget")
                }
            }
        val document =
            MarkdownDocumentFfi(
                truncated = true,
                blankLinesBefore = byteArrayOf(),
                blocks = blocks,
            )

        assertEquals(
            "a".repeat(MARKDOWN_SPEAKABLE_MAX_LENGTH),
            markdownDocumentToSpeakableText(document),
        )
    }

    @Test
    fun tableTraversalDoesNotReadRowsAfterHeaderExhaustsCharacterBudget() {
        val oversizedHeader =
            MarkdownTableCellFfi(
                listOf(MarkdownInlineFfi.Text("a".repeat(MARKDOWN_SPEAKABLE_MAX_LENGTH))),
            )
        val rows =
            object : AbstractList<List<MarkdownTableCellFfi>>() {
                override val size = 1

                override fun get(index: Int): List<MarkdownTableCellFfi> =
                    error("read a table row after the traversal budget")
            }
        val document =
            MarkdownDocumentFfi(
                truncated = true,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.Table(
                            alignments = listOf(MarkdownAlignmentFfi.NONE),
                            header = listOf(oversizedHeader),
                            rows = rows,
                        ),
                    ),
            )

        assertEquals(
            "a".repeat(MARKDOWN_SPEAKABLE_MAX_LENGTH),
            markdownDocumentToSpeakableText(document),
        )
    }

    @Test
    fun malformedDepthAndLargeLeafStayBoundedWithoutSplittingSurrogates() {
        var inline: MarkdownInlineFfi = MarkdownInlineFfi.Text("should not escape depth limit")
        repeat(MARKDOWN_MAX_INLINE_DEPTH + 1) {
            inline = MarkdownInlineFfi.Strong(listOf(inline))
        }
        val deeplyNested =
            MarkdownDocumentFfi(
                truncated = true,
                blankLinesBefore = byteArrayOf(),
                blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(inline))),
            )
        val oversized =
            MarkdownDocumentFfi(
                truncated = true,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            listOf(MarkdownInlineFfi.Text("a".repeat(MARKDOWN_SPEAKABLE_MAX_LENGTH) + "😀")),
                        ),
                    ),
            )

        assertEquals("", markdownDocumentToSpeakableText(deeplyNested))
        val projected = markdownDocumentToSpeakableText(oversized)
        assertTrue(projected.length <= MARKDOWN_SPEAKABLE_MAX_LENGTH)
        assertTrue(projected.lastOrNull()?.isHighSurrogate() != true)
    }
}
