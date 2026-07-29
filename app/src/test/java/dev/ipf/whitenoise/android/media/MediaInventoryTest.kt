package dev.ipf.whitenoise.android.media

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MessageTagFfi
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
        val projectedMedia =
            mapOf(
                "m1" to listOf(attachment("image/jpeg", "photo.jpg")),
                "m2" to listOf(attachment("video/mp4", "clip.mp4")),
                "m3" to listOf(attachment("audio/mp4", "voice.m4a")),
                "m4" to listOf(attachment("application/pdf", "doc.pdf")),
            )
        val inventory =
            MediaInventory.build(
                records = projectedMedia.keys.map(::record),
                projectedMediaByMessageId = projectedMedia,
            )
        assertEquals(listOf("m1"), inventory.images.map { it.messageIdHex })
        assertEquals(listOf("m2"), inventory.videos.map { it.messageIdHex })
        assertEquals(listOf("m3"), inventory.voice.map { it.messageIdHex })
        assertEquals(listOf("m4"), inventory.files.map { it.messageIdHex })
        assertTrue(inventory.urls.isEmpty())
    }

    @Test
    fun albumMessageWithMultipleImetaTagsCountsEachAttachment() {
        val projectedMedia =
            listOf(
                attachment("image/png", "a.png"),
                attachment("image/png", "b.png"),
            )
        val inventory =
            MediaInventory.build(
                records = listOf(record(id = "album")),
                projectedMediaByMessageId = mapOf("album" to projectedMedia),
            )
        assertEquals(2, inventory.images.size)
        assertTrue(inventory.images.all { it.source is MediaInventory.Source.Attachment })
    }

    @Test
    fun authoritativeEmptyProjectionEmitsNothingDespiteMediaTags() {
        // An id present in the projection map with an empty list is
        // authoritative — the record's compatibility tags must not be parsed.
        val tagged =
            record(id = "m").copy(
                tags = listOf(MessageTagFfi(listOf("imeta", "url https://media.example/a.png", "m image/png"))),
            )
        val inventory =
            MediaInventory.build(
                records = listOf(tagged),
                projectedMediaByMessageId = mapOf("m" to emptyList()),
            )

        assertTrue(inventory.isEmpty)
    }

    @Test
    fun bodyHttpLinkBecomesAUrlEntry() {
        val inventory = MediaInventory.build(listOf(record(id = "m", body = link("https://example.com/article"))))
        assertEquals(listOf("https://example.com/article"), inventory.urls.map { it.url })
        assertTrue(inventory.images.isEmpty())
    }

    @Test
    fun urlOnlyExtractionReturnsLinksWithoutMediaBuckets() {
        val urls =
            MediaInventory.urls(
                listOf(
                    record(
                        id = "m",
                        attachments = listOf(attachment("image/png", "a.png")),
                        body = link("https://example.com/article"),
                    ),
                ),
            )

        assertEquals(listOf("https://example.com/article"), urls.map { it.url })
    }

    @Test
    fun urlExtractionStopsAtInlineBreadthCap() {
        val body =
            MarkdownDocumentFfi(
                truncated = false,
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            List(dev.ipf.whitenoise.android.ui.MARKDOWN_MAX_CONTAINER_SIBLINGS) { MarkdownInlineFfi.Text("") } +
                                MarkdownInlineFfi.Link(
                                    dest = "https://example.com/never",
                                    title = null,
                                    children = listOf(MarkdownInlineFfi.Text("never")),
                                    classification = MarkdownLinkDestinationKindFfi.WEB,
                                ),
                        ),
                    ),
                blankLinesBefore = ByteArray(0),
            )

        assertTrue(MediaInventory.urls(listOf(record(id = "m", body = body))).isEmpty())
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
        val projectedMedia =
            mapOf(
                "first" to listOf(attachment("image/jpeg", "1.jpg")),
                "second" to listOf(attachment("image/jpeg", "2.jpg")),
            )
        val inventory =
            MediaInventory.build(
                records = listOf(record(id = "first"), record(id = "second")),
                projectedMediaByMessageId = projectedMedia,
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

    @Test
    fun deeplyNestedBlockQuotesDoNotOverflowTheStack() {
        var block: MarkdownBlockFfi = MarkdownBlockFfi.Paragraph(inlines = emptyList())
        repeat(10_000) { block = MarkdownBlockFfi.BlockQuote(listOf(block), blankLinesBefore = ByteArray(0)) }
        val body = MarkdownDocumentFfi(truncated = false, blocks = listOf(block), blankLinesBefore = ByteArray(0))

        val inventory = MediaInventory.build(listOf(record(id = "deep-quotes", body = body)))

        assertTrue(inventory.urls.isEmpty())
    }

    @Test
    fun deeplyNestedInlineEmphasisDoesNotOverflowTheStack() {
        var inline: MarkdownInlineFfi = MarkdownInlineFfi.Text("x")
        repeat(10_000) { inline = MarkdownInlineFfi.Emph(listOf(inline)) }
        val body =
            MarkdownDocumentFfi(
                truncated = false,
                blocks = listOf(MarkdownBlockFfi.Paragraph(inlines = listOf(inline))),
                blankLinesBefore = ByteArray(0),
            )

        val inventory = MediaInventory.build(listOf(record(id = "deep-emph", body = body)))

        assertTrue(inventory.urls.isEmpty())
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
            version = dev.ipf.marmotkit.EncryptedMediaVersionFfi.V1,
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
                        inlines =
                            listOf(
                                MarkdownInlineFfi.Link(
                                    dest = url,
                                    title = null,
                                    children = listOf(MarkdownInlineFfi.Text(url)),
                                    classification = MarkdownLinkDestinationKindFfi.WEB,
                                ),
                            ),
                    ),
                ),
            blankLinesBefore = ByteArray(0),
        )

    private fun text(content: String): MarkdownDocumentFfi =
        MarkdownDocumentFfi(
            truncated = false,
            blocks = listOf(MarkdownBlockFfi.Paragraph(inlines = listOf(MarkdownInlineFfi.Text(content)))),
            blankLinesBefore = ByteArray(0),
        )

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
            tags = emptyList(),
            sourceEpoch = attachments.firstOrNull()?.sourceEpoch,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 0uL,
            receivedAt = 0uL,
        )
}
