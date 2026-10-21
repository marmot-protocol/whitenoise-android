package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownCodeBlockKindFfi
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
    /** A URL's double slash moves together when the lazy-item budget lands immediately after it. */
    @Test
    fun codeChunkingKeepsDoubleSlashWithItsFollowingText() {
        val prefix = "a".repeat(4088) + "https:"
        val content = prefix + "//example.com/filename"
        val chunks = nostrReaderTextChunks(content, keepSlashWithFollowingText = true)
        assertEquals(content, chunks.joinToString(""))
        assertEquals(prefix, chunks.first())
        assertEquals("//example.com/filename", chunks.last())
        assertTrue(chunks.all { it.length <= 4096 })
    }

    /** Even a slash-only run larger than one item must make progress without losing or growing source. */
    @Test
    fun oversizedSlashRunsRemainBoundedAndReconstructExactly() {
        val content = "/".repeat(4200) + "filename"
        val chunks = nostrReaderTextChunks(content, keepSlashWithFollowingText = true)
        assertEquals(content, chunks.joinToString(""))
        assertTrue(chunks.all { it.isNotEmpty() && it.length <= 4096 })
    }

    /** Artificial lazy-item boundaries cannot strand a path's slash on the preceding item. */
    @Test
    fun codePathChunksKeepSlashWithTheFilenameAndRetainEveryCharacter() {
        val content = "a".repeat(4095) + "/" + "\uD83D\uDE00_filename".repeat(900)
        val block = MarkdownBlockFfi.CodeBlock(MarkdownCodeBlockKindFfi.FENCED, "", content)
        val document = MarkdownDocumentFfi(listOf(block), false, byteArrayOf())
        val chunks = nostrReaderBlocks(document).map { (it as MarkdownBlockFfi.CodeBlock).content }
        assertTrue(chunks.size > 1)
        assertEquals(content, chunks.joinToString(""))
        assertEquals("a".repeat(4095), chunks.first())
        assertTrue(chunks[1].startsWith("/\uD83D\uDE00_filename"))
        assertTrue(chunks.all { it.length <= 4096 && !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
    }

    /** Source newlines remain intentional break opportunities even immediately after a slash. */
    @Test
    fun codeChunkingPreservesAnExplicitNewlineAfterTheSlash() {
        val content = "a".repeat(4095) + "/\nfilename"
        val chunks = nostrReaderTextChunks(content, keepSlashWithFollowingText = true)
        assertEquals(content, chunks.joinToString(""))
        assertEquals("a".repeat(4095) + "/", chunks.first())
        assertEquals("\nfilename", chunks.last())
    }

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
        val document =
            MarkdownDocumentFfi(
                listOf(MarkdownBlockFfi.Paragraph(listOf(link, mention))),
                false,
                byteArrayOf(),
            )
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
