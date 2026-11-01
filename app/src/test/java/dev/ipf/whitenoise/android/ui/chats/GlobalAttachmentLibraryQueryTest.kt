package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.AttachmentCategoryFfi
import dev.ipf.marmotkit.AttachmentRoleFfi
import dev.ipf.whitenoise.android.core.MessageSearchConstraints
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.search.GlobalSearchEpochBounds
import dev.ipf.whitenoise.android.ui.medialibrary.accountEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How search state becomes the native account query and the host-side admission rule (#2000). */
class GlobalAttachmentLibraryQueryTest {
    /** Chat, sender and date filters travel to MDK; date bounds become inclusive timeline seconds. */
    @Test
    fun filtersBecomeTheNativeQuery() {
        val query =
            globalAttachmentLibraryQuery(
                scopedChatIds = listOf("BB", "aa"),
                constraints =
                    MessageSearchConstraints(
                        senderIds = setOf("cc"),
                        dateBounds = GlobalSearchEpochBounds(10_000L, 20_000L),
                    ),
                kinds = setOf(GlobalSearchContentKind.FILES_DOCUMENTS),
                rawQuery = "  Report ",
            )

        val native = query.toFfi()
        assertEquals(listOf("aa", "bb"), native.groups)
        assertEquals(listOf("cc"), native.senders)
        assertEquals(10uL, native.minTimelineAt)
        assertEquals(19uL, native.maxTimelineAt)
        assertEquals("report", query.needle)
    }

    /** No chat filter means every chat; a chat filter matching nothing means no read at all. */
    @Test
    fun chatScopeIsAllOrNothingWhenUnfiltered() {
        val all = globalAttachmentLibraryQuery(null, null, setOf(GlobalSearchContentKind.ANY_ATTACHMENT), "")
        val none = globalAttachmentLibraryQuery(emptyList(), null, setOf(GlobalSearchContentKind.ANY_ATTACHMENT), "")

        assertNull(all.groups)
        assertTrue(all.toFfi().groups.isEmpty())
        assertFalse(all.matchesNothing)
        assertTrue(none.matchesNothing)
    }

    /** Over MDK's per-list limit the selection is narrowed on the host, so each read stays bounded. */
    @Test
    fun oversizedSelectionsAreNarrowedOnTheHost() {
        val chats = (0 until 150).map { "%02x".format(it) }
        val query = globalAttachmentLibraryQuery(chats, null, setOf(GlobalSearchContentKind.ANY_ATTACHMENT), "")

        assertTrue(query.toFfi().groups.isEmpty())
        assertTrue(query.admits(accountEntry(1, group = "95")))
        assertFalse(query.admits(accountEntry(1, group = "zz")))
    }

    /** Only shared, accepted slots of a selected kind are listed; emoji artwork and placeholders never are. */
    @Test
    fun admitsOnlySharedAcceptedSlotsOfTheSelectedKind() {
        val files = globalAttachmentLibraryQuery(null, null, setOf(GlobalSearchContentKind.FILES_DOCUMENTS), "")
        val any = globalAttachmentLibraryQuery(null, null, setOf(GlobalSearchContentKind.ANY_ATTACHMENT), "")

        assertTrue(files.admits(accountEntry(1, category = AttachmentCategoryFfi.FILE)))
        assertFalse(files.admits(accountEntry(1, category = AttachmentCategoryFfi.IMAGE)))
        assertTrue(any.admits(accountEntry(1, category = AttachmentCategoryFfi.AUDIO)))
        assertFalse(any.admits(accountEntry(1, role = AttachmentRoleFfi.INLINE_EMOJI)))
        assertFalse(any.admits(accountEntry(1, category = AttachmentCategoryFfi.REJECTED)))
        assertFalse(any.admits(accountEntry(1, metadataLimited = true)))
    }

    /** The typed text narrows by file name, case-insensitively; message bodies are not searched here. */
    @Test
    fun typedTextMatchesFileNames() {
        val query = globalAttachmentLibraryQuery(null, null, setOf(GlobalSearchContentKind.ANY_ATTACHMENT), "PHOTO-7")

        assertTrue(query.admits(accountEntry(7)))
        assertFalse(query.admits(accountEntry(8)))
    }

    /** A library item keeps the full native identity the openers revalidate, titled by its chat. */
    @Test
    fun libraryItemKeepsNativeIdentity() {
        val item = accountEntry(3, group = "aa", index = 2u).toLibraryItem { "Design review" }

        assertEquals("aa", item?.groupIdHex)
        assertEquals("message-3", item?.messageIdHex)
        assertEquals("source-3", item?.sourceMessageIdHex)
        assertEquals(2, item?.attachmentIndex)
        assertEquals("Design review", item?.chatTitle)
        assertEquals("photo-3.png", item?.label)
    }
}
