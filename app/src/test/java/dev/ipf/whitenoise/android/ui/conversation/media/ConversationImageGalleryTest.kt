package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationImageGalleryTest {
    @Test
    fun separateImageMessagesUseTheConversationGalleryAtTheTappedPage() {
        val newest = page("newest", attachmentIndex = 0, recordedAt = 300uL)
        val tapped = page("middle", attachmentIndex = 0, recordedAt = 200uL)
        val oldest = page("oldest", attachmentIndex = 0, recordedAt = 100uL)

        val gallery =
            visualMediaViewerGallery(
                conversationVisualPages = listOf(newest, tapped, oldest),
                messagePages = listOf(tapped),
                tappedAttachmentIndex = 0,
            )

        assertEquals(listOf("newest", "middle", "oldest"), gallery.pages.map { it.messageIdHex })
        assertEquals(1, gallery.startIndex)
    }

    @Test
    fun imageAlbumUsesTheSharedMediaOrderAndResolvesTheAttachmentIndex() {
        val newer = page("newer", attachmentIndex = 0, recordedAt = 300uL)
        val albumFirst = page("album", attachmentIndex = 0, recordedAt = 200uL)
        val albumSecond = page("album", attachmentIndex = 1, recordedAt = 200uL)
        val older = page("older", attachmentIndex = 0, recordedAt = 100uL)
        val sharedMediaOrder = listOf(newer, albumSecond, albumFirst, older)

        val gallery =
            visualMediaViewerGallery(
                conversationVisualPages = sharedMediaOrder,
                messagePages = listOf(albumFirst, albumSecond),
                tappedAttachmentIndex = 0,
            )

        assertEquals(sharedMediaOrder, gallery.pages)
        assertEquals(2, gallery.startIndex)
    }

    @Test
    fun singleImageConversationKeepsAOnePageGallery() {
        val only = page("only", attachmentIndex = 0, recordedAt = 100uL)

        val gallery =
            visualMediaViewerGallery(
                conversationVisualPages = listOf(only),
                messagePages = listOf(only),
                tappedAttachmentIndex = 0,
            )

        assertEquals(listOf(only), gallery.pages)
        assertEquals(0, gallery.startIndex)
    }

    @Test
    fun optimisticImageMissingFromTheProjectionIsInsertedNewestFirst() {
        val optimistic = page("optimistic", attachmentIndex = 0, recordedAt = 300uL, mine = true)
        val confirmed = page("confirmed", attachmentIndex = 0, recordedAt = 100uL)

        val gallery =
            visualMediaViewerGallery(
                conversationVisualPages = listOf(confirmed),
                messagePages = listOf(optimistic),
                tappedAttachmentIndex = 0,
            )

        assertEquals(listOf("optimistic", "confirmed"), gallery.pages.map { it.messageIdHex })
        assertTrue(gallery.pages.first().mine)
        assertEquals(0, gallery.startIndex)
    }

    @Test
    fun mixedImageVideoAlbumUsesTheConversationVisualGallery() {
        val newerVideo = page("newer-video", attachmentIndex = 0, recordedAt = 300uL, mediaType = "video/mp4")
        val image = page("mixed", attachmentIndex = 0, recordedAt = 200uL)
        val video = page("mixed", attachmentIndex = 1, recordedAt = 200uL, mediaType = "video/mp4")
        val otherImage = page("other", attachmentIndex = 0, recordedAt = 100uL)
        val conversationVisualOrder = listOf(newerVideo, video, image, otherImage)

        val gallery =
            visualMediaViewerGallery(
                conversationVisualPages = conversationVisualOrder,
                messagePages = listOf(image, video),
                tappedAttachmentIndex = 1,
            )

        assertEquals(conversationVisualOrder, gallery.pages)
        assertEquals(1, gallery.startIndex)
    }

    private fun page(
        messageIdHex: String,
        attachmentIndex: Int,
        recordedAt: ULong,
        mine: Boolean = false,
        mediaType: String = "image/jpeg",
    ) = MediaViewerPage(
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        reference = reference("$messageIdHex-$attachmentIndex", mediaType),
        mine = mine,
        sender = "sender-$messageIdHex",
        recordedAt = recordedAt,
    )

    private fun reference(
        fileName: String,
        mediaType: String,
    ) = MediaAttachmentReferenceFfi(
        locators = emptyList(),
        ciphertextSha256 = "aa".repeat(32),
        plaintextSha256 = "bb".repeat(32),
        nonceHex = "cc".repeat(12),
        fileName = fileName,
        mediaType = mediaType,
        version = EncryptedMediaVersionFfi.V1,
        sourceEpoch = 1uL,
        dim = null,
        thumbhash = null,
    )
}
