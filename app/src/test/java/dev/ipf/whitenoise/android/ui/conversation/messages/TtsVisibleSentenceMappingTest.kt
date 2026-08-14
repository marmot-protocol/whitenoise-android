package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.marmotkit.MarkdownAlignmentFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownCodeBlockKindFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.marmotkit.MarkdownListItemFfi
import dev.ipf.marmotkit.MarkdownListKindFfi
import dev.ipf.marmotkit.MarkdownTableCellFfi
import dev.ipf.whitenoise.android.ui.markdownDocumentToSpeakableText
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TtsVisibleSentenceMappingTest {
    @Test
    fun tableDrivenVisibleToSpeakableMappingCases() {
        val locale = Locale.US
        mappingCases().forEach { case ->
            assertEquals(
                case.label,
                case.expectedSentenceIndex,
                speakableSentenceIndexAtVisibleOffset(
                    visibleText = case.visibleText,
                    speakableText = case.speakableText,
                    visibleOffset = case.visibleOffset,
                    locale = locale,
                ),
            )
        }
    }

    @Test
    @Suppress("LongMethod")
    fun markdownListAndTableSpeakableProjectionsStayAligned() {
        val listDocument =
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
                                                    listOf(MarkdownInlineFfi.Text("First item")),
                                                ),
                                            ),
                                        checked = null,
                                        blankLinesBefore = byteArrayOf(),
                                    ),
                                    MarkdownListItemFfi(
                                        blocks =
                                            listOf(
                                                MarkdownBlockFfi.Paragraph(
                                                    listOf(MarkdownInlineFfi.Text("Second item")),
                                                ),
                                            ),
                                        checked = true,
                                        blankLinesBefore = byteArrayOf(),
                                    ),
                                ),
                        ),
                    ),
            )
        val listSpeakable = markdownDocumentToSpeakableText(listDocument)
        assertEquals("First item. Second item.", listSpeakable)
        assertEquals(
            1,
            speakableSentenceIndexAtVisibleOffset(
                visibleText = listSpeakable,
                speakableText = listSpeakable,
                visibleOffset = listSpeakable.indexOf("Second"),
                locale = Locale.US,
            ),
        )

        val tableDocument =
            MarkdownDocumentFfi(
                truncated = false,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.Table(
                            alignments = listOf(MarkdownAlignmentFfi.NONE, MarkdownAlignmentFfi.NONE),
                            header =
                                listOf(
                                    MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text("H1"))),
                                    MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text("H2"))),
                                ),
                            rows =
                                listOf(
                                    listOf(
                                        MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text("C1"))),
                                        MarkdownTableCellFfi(listOf(MarkdownInlineFfi.Text("C2"))),
                                    ),
                                ),
                        ),
                    ),
            )
        val tableSpeakable = markdownDocumentToSpeakableText(tableDocument)
        assertEquals("H1. H2. C1. C2.", tableSpeakable)
        assertEquals(
            3,
            speakableSentenceIndexAtVisibleOffset(
                visibleText = tableSpeakable,
                speakableText = tableSpeakable,
                visibleOffset = tableSpeakable.indexOf("C2"),
                locale = Locale.US,
            ),
        )
    }

    @Test
    fun surrogatePairOffsetStaysInsideContainingSentence() {
        val text = "Before \uD83D\uDE00 after. Next."

        assertEquals(0, speakableSentenceIndexAtVisibleOffset(text, text, text.indexOf("\uD83D"), Locale.US))
        assertEquals(1, speakableSentenceIndexAtVisibleOffset(text, text, text.indexOf("Next"), Locale.US))
    }

    private data class MappingCase(
        val label: String,
        val visibleText: String,
        val speakableText: String,
        val visibleOffset: Int?,
        val expectedSentenceIndex: Int,
    )

    @Suppress("LongMethod")
    private fun mappingCases(): List<MappingCase> {
        val mentionVisible = "Hello Alice. Later."
        val mentionSpeakable = mentionVisible

        val codeDocument =
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
        val codeVisible = "return value;"
        val codeSpeakable = markdownDocumentToSpeakableText(codeDocument)

        val linkDocument =
            MarkdownDocumentFfi(
                truncated = false,
                blankLinesBefore = byteArrayOf(),
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            inlines =
                                listOf(
                                    MarkdownInlineFfi.Text("See "),
                                    MarkdownInlineFfi.Link(
                                        dest = "https://example.com",
                                        title = null,
                                        children = listOf(MarkdownInlineFfi.Text("docs")),
                                        classification = MarkdownLinkDestinationKindFfi.WEB,
                                    ),
                                    MarkdownInlineFfi.Text(" now. Later."),
                                ),
                        ),
                    ),
            )
        val linkSpeakable = markdownDocumentToSpeakableText(linkDocument)
        val linkVisible = "See docs now. Later."

        val zwjText = "Family \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67. Next."
        val combiningText = "Resume\u0301 now. Later."

        return listOf(
            MappingCase(
                label = "plain text second sentence",
                visibleText = "First sentence. Second sentence.",
                speakableText = "First sentence. Second sentence.",
                visibleOffset = "First sentence. Second sentence.".indexOf("Second"),
                expectedSentenceIndex = 1,
            ),
            MappingCase(
                label = "mention-like visible label",
                visibleText = mentionVisible,
                speakableText = mentionSpeakable,
                visibleOffset = mentionVisible.indexOf("Later"),
                expectedSentenceIndex = 1,
            ),
            MappingCase(
                label = "list second item",
                visibleText = "First item. Second item.",
                speakableText = "First item. Second item.",
                visibleOffset = "First item. Second item.".indexOf("Second"),
                expectedSentenceIndex = 1,
            ),
            MappingCase(
                label = "table last cell",
                visibleText = "H1. H2. C1. C2.",
                speakableText = "H1. H2. C1. C2.",
                visibleOffset = "H1. H2. C1. C2.".indexOf("C2"),
                expectedSentenceIndex = 3,
            ),
            MappingCase(
                label = "code block",
                visibleText = codeVisible,
                speakableText = codeSpeakable,
                visibleOffset = codeVisible.indexOf("return"),
                expectedSentenceIndex = 0,
            ),
            MappingCase(
                label = "link second sentence",
                visibleText = linkVisible,
                speakableText = linkSpeakable,
                visibleOffset = linkVisible.indexOf("Later"),
                expectedSentenceIndex = 1,
            ),
            MappingCase(
                label = "zwj emoji sentence",
                visibleText = zwjText,
                speakableText = zwjText,
                visibleOffset = zwjText.indexOf("\uD83D"),
                expectedSentenceIndex = 0,
            ),
            MappingCase(
                label = "combining grapheme sentence",
                visibleText = combiningText,
                speakableText = combiningText,
                visibleOffset = combiningText.indexOf("Later"),
                expectedSentenceIndex = 1,
            ),
            MappingCase(
                label = "truncated mapping unavailable",
                visibleText = "Visible only.",
                speakableText = "Different speakable.",
                visibleOffset = 2,
                expectedSentenceIndex = 0,
            ),
            MappingCase(
                label = "null offset fallback",
                visibleText = "First. Second.",
                speakableText = "First. Second.",
                visibleOffset = null,
                expectedSentenceIndex = 0,
            ),
            MappingCase(
                label = "ambiguous visible to speakable match",
                visibleText = "Intro. Repeat.",
                speakableText = "Repeat. Repeat.",
                visibleOffset = "Intro. Repeat.".indexOf("Repeat"),
                expectedSentenceIndex = 0,
            ),
            MappingCase(
                label = "out of bounds offset fallback",
                visibleText = "First. Second.",
                speakableText = "First. Second.",
                visibleOffset = Int.MAX_VALUE,
                expectedSentenceIndex = 0,
            ),
        )
    }
}
