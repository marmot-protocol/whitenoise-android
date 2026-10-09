package dev.ipf.whitenoise.android.ui.medialibrary

import dev.ipf.marmotkit.AttachmentCategoryFfi
import dev.ipf.marmotkit.AttachmentRoleFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gallery filtering consumes the native role without reconstructing emoji tags or changing cursors. */
class AttachmentLibraryRoleTest {
    /** Hiding artwork in a mixed album must not renumber the shared slots used for acquisition. */
    @Test
    fun mixedAlbumKeepsSharedSlotsInNativeOrder() {
        val rows =
            listOf(
                historyEntry(1, 0u, role = AttachmentRoleFfi.INLINE_EMOJI),
                historyEntry(1, 2u),
                historyEntry(1, 4u, role = AttachmentRoleFfi.INLINE_EMOJI),
                historyEntry(1, 5u, category = AttachmentCategoryFfi.VIDEO),
                historyEntry(1, 7u, category = AttachmentCategoryFfi.AUDIO),
                historyEntry(1, 9u, category = AttachmentCategoryFfi.FILE),
            )
        val tiles = attachmentLibraryTiles(rows, "sender")
        assertEquals(listOf(2, 5), tiles.visuals.map { it.attachmentIndex })
        assertEquals(listOf(2), tiles.images.map { it.attachmentIndex })
        assertEquals(listOf(5), tiles.videos.map { it.attachmentIndex })
        assertEquals(listOf(7), tiles.voice.map { it.attachmentIndex })
        assertEquals(listOf(9), tiles.files.map { it.attachmentIndex })
        assertTrue(tiles.visuals.all { it.messageIdHex == "message-1" && it.mine })
    }

    /** An artwork-only first page is partial, and each explicit continuation reads one native page. */
    @Test
    fun artworkPagesKeepOlderSharedAttachmentsReachable() =
        runTest {
            val artwork = (350 downTo 1).map { historyEntry(it, role = AttachmentRoleFfi.INLINE_EMOJI) }
            val photo = historyEntry(0, 4u)
            val source = GroupAttachmentFixture(artwork + photo, size = 100)
            val pager = GroupAttachmentPager(source)
            try {
                pager.refresh()
                repeat(3) { page ->
                    assertTrue(attachmentLibraryTiles(pager.state.entries, null).isEmpty)
                    assertTrue(pager.state.hasMore)
                    assertEquals(page + 1, source.calls)
                    pager.loadMore()
                }
                assertFalse(pager.state.hasMore)
                assertEquals(
                    listOf("message-0"),
                    attachmentLibraryTiles(pager.state.entries, null).images.map { it.messageIdHex },
                )
                assertEquals(artwork + photo, pager.state.entries)
            } finally {
                pager.close()
            }
            assertTrue(source.cursors.all { it.closes == 1 })
            assertTrue(source.versions.all { it.closes == 1 })
        }

    /** Native role invalidation rebuilds loaded slots in both directions, even after exhaustion. */
    @Test
    fun roleChangesReplaceLoadedTilesAndKeepThePageBoundary() =
        runTest {
            for (exhausted in listOf(false, true)) {
                val rows = (30 downTo 0).map { historyEntry(it) }
                val source = GroupAttachmentFixture(rows)
                val pager = GroupAttachmentPager(source)
                try {
                    pager.refresh()
                    if (exhausted) while (pager.state.hasMore) pager.loadMore()
                    val loaded = pager.state.entries.size
                    source.rows = rows.map { it.copy(role = AttachmentRoleFfi.INLINE_EMOJI) }
                    source.removals++
                    pager.refresh()
                    assertTrue(attachmentLibraryTiles(pager.state.entries, null).isEmpty)
                    assertEquals(loaded, pager.state.entries.size)
                    assertEquals(!exhausted, pager.state.hasMore)
                    source.rows = rows
                    source.removals++
                    pager.refresh()
                    assertEquals(rows.take(loaded), pager.state.entries)
                    assertEquals(loaded, attachmentLibraryTiles(pager.state.entries, null).images.size)
                    assertEquals(!exhausted, pager.state.hasMore)
                } finally {
                    pager.close()
                }
                assertTrue(source.cursors.all { it.closes == 1 })
                assertTrue(source.versions.all { it.closes == 1 })
            }
        }
}
