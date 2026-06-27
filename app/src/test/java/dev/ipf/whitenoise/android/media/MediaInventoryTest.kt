package dev.ipf.whitenoise.android.media

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MediaInventoryTest {
    @Before
    fun setUp() {
        MediaInventory.clear()
    }

    @Test
    fun classifiesAttachmentsByMimeIntoTypedBuckets() {
        val inventory =
            MediaInventory.build(
                listOf(
                    record(id = "m1", attachments = listOf(attachment("image/jpeg", "photo.jpg"))),
                    record(id = "m2", attachments = listOf(attachment("video/mp4", "clip.mp4"))),
                    record(id = "m3", attachments = listOf(attachment("audio/mp4", "voice.m4a"))),
                    record(id = "m4", attachments = listOf(attachment("application/pdf", "doc.pdf"))),
                ),
            )
        assertEquals(listOf("m1"), inventory.images.map { it.messageIdHex })
        assertEquals(listOf("m2"), inventory.videos.map { it.messageIdHex })
        assertEquals(listOf("m3"), inventory.voice.map { it.messageIdHex })
        assertEquals(listOf("m4"), inventory.files.map { it.messageIdHex })
        assertTrue(inventory.urls.isEmpty())
    }

    @Test
    fun albumMessageWithMultipleImetaTagsCountsEachAttachment() {
        val inventory =
            MediaInventory.build(
                listOf(record(id = "album", attachments = listOf(attachment("image/png", "a.png"), attachment("image/png", "b.png")))),
            )
        assertEquals(2, inventory.images.size)
        assertTrue(inventory.images.all { it.source is MediaInventory.Source.Attachment })
    }

    @Test
    fun bodyHttpLinkBecomesAUrlEntry() {
        val inventory = MediaInventory.build(listOf(record(id = "m", body = link("https://example.com/article"))))
        assertEquals(listOf("https://example.com/article"), inventory.urls.map { it.url })
        assertTrue(inventory.images.isEmpty())
    }

    @Test
    fun bodyImageUrlIsClassifiedAsImageNotUrl() {
        // A bare image link counts as an image (LinkedUrl source) and is kept out
        // of the URLs bucket so the same link isn't shown twice.
        val inventory = MediaInventory.build(listOf(record(id = "m", body = link("https://cdn.example.com/cat.JPG?w=200"))))
        assertTrue(inventory.urls.isEmpty())
        assertEquals(1, inventory.images.size)
        assertEquals(MediaInventory.Source.LinkedUrl("https://cdn.example.com/cat.JPG?w=200"), inventory.images.single().source)
    }

    @Test
    fun cleartextBodyImageUrlStaysPlainUrl() {
        val inventory = MediaInventory.build(listOf(record(id = "m", body = link("http://cdn.example.com/cat.JPG"))))

        assertTrue(inventory.images.isEmpty())
        assertEquals(listOf("http://cdn.example.com/cat.JPG"), inventory.urls.map { it.url })
    }

    @Test
    fun imageExtensionInQueryOnlyIsAUrlNotAnImage() {
        // The image extension is in the query, not the path — it's a normal link
        // and must stay in the URLs bucket, not be misread as an image.
        val inventory = MediaInventory.build(listOf(record(id = "m", body = link("https://example.com/article?file=cat.jpg"))))
        assertTrue(inventory.images.isEmpty())
        assertEquals(listOf("https://example.com/article?file=cat.jpg"), inventory.urls.map { it.url })
    }

    @Test
    fun nonHttpLinksAreIgnored() {
        val inventory = MediaInventory.build(listOf(record(id = "m", body = link("mailto:someone@example.com"))))
        assertTrue(inventory.isEmpty)
    }

    @Test
    fun plainTextMessageProducesEmptyInventory() {
        val inventory = MediaInventory.build(listOf(record(id = "m", body = text("just a normal message"))))
        assertTrue(inventory.isEmpty)
    }

    @Test
    fun preservesTimelineOrderAcrossRecords() {
        val inventory =
            MediaInventory.build(
                listOf(
                    record(id = "first", attachments = listOf(attachment("image/jpeg", "1.jpg"))),
                    record(id = "second", attachments = listOf(attachment("image/jpeg", "2.jpg"))),
                ),
            )
        assertEquals(listOf("first", "second"), inventory.images.map { it.messageIdHex })
    }

    @Test
    fun mediaInventoryClearDropsCachedRecordEntries() {
        val first = MediaInventory.build(listOf(record(id = "m", body = link("https://example.com/one"))))
        MediaInventory.clear()
        val second = MediaInventory.build(listOf(record(id = "m", body = link("https://example.com/two"))))

        assertEquals(listOf("https://example.com/one"), first.urls.map { it.url })
        assertEquals(listOf("https://example.com/two"), second.urls.map { it.url })
    }

    // --- builders ---

    private fun attachment(
        mime: String,
        fileName: String,
    ): MediaAttachmentReferenceFfi =
        MediaAttachmentReferenceFfi(
            locators = listOf(MediaLocatorFfi(kind = "blossom-v1", value = "https://blossom.example.com/$fileName")),
            ciphertextSha256 = "a".repeat(64),
            plaintextSha256 = "b".repeat(64),
            nonceHex = "c".repeat(24),
            fileName = fileName,
            mediaType = mime,
            version = "encrypted-media-v1",
            sourceEpoch = 0uL,
            dim = null,
            thumbhash = null,
        )

    private fun link(url: String): MarkdownDocumentFfi =
        MarkdownDocumentFfi(
            truncated = false,
            blocks =
                listOf(
                    MarkdownBlockFfi.Paragraph(
                        inlines = listOf(MarkdownInlineFfi.Link(dest = url, title = null, children = listOf(MarkdownInlineFfi.Text(url)))),
                    ),
                ),
        )

    private fun text(content: String): MarkdownDocumentFfi =
        MarkdownDocumentFfi(truncated = false, blocks = listOf(MarkdownBlockFfi.Paragraph(inlines = listOf(MarkdownInlineFfi.Text(content)))))

    private fun record(
        id: String,
        attachments: List<MediaAttachmentReferenceFfi> = emptyList(),
        body: MarkdownDocumentFfi = text(""),
    ): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = id,
            direction = "incoming",
            groupIdHex = "group",
            sender = "sender-$id",
            plaintext = "",
            contentTokens = body,
            kind = 9uL,
            tags = attachments.map { MediaReferenceParser.toImetaTag(it) },
            recordedAt = 0uL,
            receivedAt = 0uL,
        )
}
