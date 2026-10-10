package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NostrEventBodyImagesTest {
    @Test
    fun nativeImageNodesIncludeExtensionlessUrlsAndDeduplicateMetadata() {
        val url = "https://images.example/resource"
        val document =
            document(MarkdownBlockFfi.Paragraph(listOf(image(url), image(url), image("https://images.example/second"))))
        assertEquals(listOf(url, "https://images.example/second"), nostrEventImageCandidates(listOf(url), document))
    }

    @Test
    fun linksAreOnlyHintsAndUnsafeImagesAreRejected() {
        val document =
            document(
                MarkdownBlockFfi.Paragraph(
                    listOf(
                        image("http://images.example/clear.jpg"),
                        image("https://127.0.0.1/private.jpg"),
                        image("https://user:pass@images.example/credential.jpg"),
                        image("https://images.example:8443/port.jpg"),
                        link("https://images.example/PHOTO.JPG?version=2"),
                        link("https://images.example/article"),
                        MarkdownInlineFfi.Code("https://images.example/literal.jpg"),
                    ),
                ),
            )
        assertEquals(
            listOf("https://images.example/PHOTO.JPG?version=2"),
            nostrEventImageCandidates(emptyList(), document),
        )
    }

    @Test
    fun nestedStructureKeepsOneCombinedEightImageLimit() {
        val urls = (0..12).map { "https://images.example/$it" }
        val document = document(
            MarkdownBlockFfi.BlockQuote(
                blocks = listOf(MarkdownBlockFfi.Paragraph(urls.map(::image))),
                blankLinesBefore = byteArrayOf(),
            ),
        )
        assertEquals(urls.take(MAX_READER_IMAGES), nostrEventImageCandidates(urls.take(3), document))
    }

    @Test
    fun literalTreesStopAtTheTraversalBudget() {
        val literals = List(256) { MarkdownInlineFfi.Text("Not a link") }
        val blocks = List(4) { MarkdownBlockFfi.Paragraph(literals) } +
            MarkdownBlockFfi.Paragraph(listOf(image("https://images.example/after-budget")))
        assertEquals(
            emptyList<String>(),
            nostrEventImageCandidates(emptyList(), MarkdownDocumentFfi(blocks, false, byteArrayOf())),
        )
    }

    @Test
    fun excessiveInlineDepthDoesNotDiscoverAFakeNestedImage() {
        var nested: MarkdownInlineFfi = image("https://images.example/too-deep")
        repeat(70) { nested = MarkdownInlineFfi.Emph(listOf(nested)) }
        assertEquals(
            emptyList<String>(),
            nostrEventImageCandidates(emptyList(), document(MarkdownBlockFfi.Paragraph(listOf(nested)))),
        )
    }

    @Test
    fun cardParsingLimitCountsUtf8BytesBeforeNativeParsing() {
        assertNotNull(boundedNostrImageBody("a".repeat(128 * 1024)))
        assertNull(boundedNostrImageBody("a".repeat(128 * 1024 + 1)))
        assertNull(boundedNostrImageBody("é".repeat(128 * 1024)))
    }

    private fun image(url: String) =
        MarkdownInlineFfi.Image(
            dest = url,
            title = null,
            alt = listOf(MarkdownInlineFfi.Text("Photo")),
            classification = MarkdownLinkDestinationKindFfi.WEB,
        )

    private fun link(url: String) =
        MarkdownInlineFfi.Link(
            dest = url,
            title = null,
            children = listOf(MarkdownInlineFfi.Text("Original link")),
            classification = MarkdownLinkDestinationKindFfi.WEB,
        )

    private fun document(block: MarkdownBlockFfi) = MarkdownDocumentFfi(listOf(block), false, byteArrayOf())
}
