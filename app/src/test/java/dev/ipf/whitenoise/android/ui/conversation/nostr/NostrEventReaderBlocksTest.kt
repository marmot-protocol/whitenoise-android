package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrEventReaderBlocksTest {
    @Test
    fun oversizedNestedMarkdownFallsBackRatherThanElidingEventContent() {
        val paragraph = MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text("context ".repeat(1000))))
        val quote = MarkdownBlockFfi.BlockQuote(blocks = listOf(paragraph), blankLinesBefore = byteArrayOf())
        assertFalse(nostrReaderCanFormat(MarkdownDocumentFfi(listOf(quote), false, byteArrayOf())))
        assertTrue(nostrReaderCanFormat(MarkdownDocumentFfi(listOf(paragraph), false, byteArrayOf())))
    }

    @Test
    fun entireLargeUnicodeBodySurvivesBoundedLazyItems() {
        val text = ("word " + "\uD83D\uDE00").repeat(20_000) + "Final paragraph"
        val chunks = nostrReaderTextChunks(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 4096 && !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
    }

    @Test
    fun chunkingRetainsFormattingDestinationsAndAtomicProfileMentions() {
        val body = "Readable text ".repeat(10_000)
        val link =
            MarkdownInlineFfi.Link(
                "https://example.com/source",
                null,
                listOf(MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text(body)))),
                MarkdownLinkDestinationKindFfi.WEB,
            )
        val mention =
            MarkdownInlineFfi.NostrMention(
                MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, "npub1example"),
            )
        val document = MarkdownDocumentFfi(listOf(MarkdownBlockFfi.Paragraph(listOf(link, mention))), false, byteArrayOf())
        val inlines = nostrReaderBlocks(document).flatMap { (it as MarkdownBlockFfi.Paragraph).inlines }
        val links = inlines.filterIsInstance<MarkdownInlineFfi.Link>()
        assertTrue(links.size > 1)
        assertTrue(links.all { it.dest == link.dest && it.classification == link.classification })
        val joinedText =
            links.joinToString("") { fragment ->
                val strong = fragment.children.single() as MarkdownInlineFfi.Strong
                (strong.children.single() as MarkdownInlineFfi.Text).content
            }
        assertEquals(body, joinedText)
        assertEquals(listOf(mention), inlines.filterIsInstance<MarkdownInlineFfi.NostrMention>())
    }
}
