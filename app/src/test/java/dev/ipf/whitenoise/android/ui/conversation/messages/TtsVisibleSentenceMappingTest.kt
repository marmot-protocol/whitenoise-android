package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.whitenoise.android.ui.markdownDocumentToSpeakableText
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TtsVisibleSentenceMappingTest {
    @Test
    fun plainTextMapsSelectionOffsetToContainingSentence() {
        val text = "First sentence. Second sentence. Third."
        val offset = text.indexOf("Second")

        assertEquals(1, speakableSentenceIndexAtVisibleOffset(text, text, offset, Locale.US))
    }

    @Test
    fun markdownLinkLabelMapsToSpeakableSentence() {
        val document =
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
        val visible = "See docs now. Later."
        val speakable = markdownDocumentToSpeakableText(document)
        val offset = visible.indexOf("Later")

        assertEquals(1, speakableSentenceIndexAtVisibleOffset(visible, speakable, offset, Locale.US))
    }

    @Test
    fun unavailableMappingFallsBackToFirstSentence() {
        val visible = "Visible only."
        val speakable = "Completely different speakable text."

        assertEquals(0, speakableSentenceIndexAtVisibleOffset(visible, speakable, 3, Locale.US))
        assertEquals(0, speakableSentenceIndexAtVisibleOffset(visible, speakable, null, Locale.US))
    }

    @Test
    fun ambiguousVisibleToSpeakableMatchFallsBackToFirstSentence() {
        assertEquals(
            0,
            speakableSentenceIndexAtVisibleOffset(
                visibleText = "Intro. Repeat.",
                speakableText = "Repeat. Repeat.",
                visibleOffset = "Intro. Repeat.".indexOf("Repeat"),
                locale = Locale.US,
            ),
        )
    }

    @Test
    fun outOfBoundsVisibleOffsetFallsBackToFirstSentence() {
        assertEquals(
            0,
            speakableSentenceIndexAtVisibleOffset(
                visibleText = "First. Second.",
                speakableText = "First. Second.",
                visibleOffset = Int.MAX_VALUE,
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
}
