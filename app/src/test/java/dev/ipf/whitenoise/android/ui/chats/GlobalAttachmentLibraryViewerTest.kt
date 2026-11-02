package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.core.GlobalAttachmentItem
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerPage
import dev.ipf.whitenoise.android.ui.medialibrary.accountEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library viewer pages through the listed results across chats and drops items the library loses (#2000). */
class GlobalAttachmentLibraryViewerTest {
    private val items =
        listOf(
            item(5, "aa"),
            item(4, "bb", "application/pdf"),
            item(3, "bb"),
            item(3, "aa", "video/mp4"),
            item(2, "cc"),
            item(1, "aa"),
        )

    /** Photos and videos of every chat open as one gallery in result order, starting at the tapped item. */
    @Test
    fun galleryCrossesChatsInResultOrder() {
        val gallery = checkNotNull(libraryViewerGallery(items, items[2], { true }, ::page))

        assertEquals(
            listOf("aa:5", "bb:3", "aa:3", "cc:2", "aa:1"),
            gallery.pages.map { "${it.groupIdHex}:${it.messageIdHex.removePrefix("message-")}" },
        )
        assertEquals(1, gallery.startIndex)
    }

    /** Equal message IDs in two chats stay two pages, and the tap starts at the one in its own chat. */
    @Test
    fun equalMessageIdsInTwoChatsStayDistinct() {
        val gallery = checkNotNull(libraryViewerGallery(items, items[3], { true }, ::page))

        assertEquals("aa", gallery.pages[gallery.startIndex].groupIdHex)
        assertEquals(2, gallery.startIndex)
    }

    /** A chat the library cannot name is left out, and tapping one of its items opens no gallery. */
    @Test
    fun unknownChatsAreLeftOut() {
        val known = { group: String -> group != "cc" }

        val gallery = checkNotNull(libraryViewerGallery(items, items[0], known, ::page))
        assertTrue(gallery.pages.none { it.groupIdHex == "cc" })
        assertNull(libraryViewerGallery(items, items[4], known, ::page))
    }

    /** Pages whose item left the results leave the viewer; the rest keep their order. */
    @Test
    fun livePagesDropItemsTheLibraryNoLongerLists() {
        val pages = checkNotNull(libraryViewerGallery(items, items[0], { true }, ::page)).pages

        val live = libraryViewerLivePages(pages, items - items[2] - items[4])

        assertEquals(listOf("aa", "aa", "aa"), live.map { it.groupIdHex })
        assertEquals(listOf("message-5", "message-3", "message-1"), live.map { it.messageIdHex })
        assertTrue(libraryViewerLivePages(pages, emptyList()).isEmpty())
    }

    /** A library item for one native slot, listed with [mediaType]. */
    private fun item(
        id: Int,
        group: String,
        mediaType: String = "image/png",
    ): GlobalAttachmentItem {
        val item = checkNotNull(accountEntry(id, group = group).toLibraryItem { it })
        return item.copy(mediaType = mediaType)
    }

    /** The viewer page the opener builds for an item. */
    private fun page(item: GlobalAttachmentItem): MediaViewerPage =
        MediaViewerPage(
            messageIdHex = item.messageIdHex,
            attachmentIndex = item.attachmentIndex,
            reference = checkNotNull(item.reference),
            mine = false,
            sender = item.sender,
            recordedAt = item.timelineAt,
            groupIdHex = item.groupIdHex,
        )
}
