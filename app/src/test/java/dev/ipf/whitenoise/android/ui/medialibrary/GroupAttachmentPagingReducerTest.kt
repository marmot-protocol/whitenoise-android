@file:Suppress("MaxLineLength")

package dev.ipf.whitenoise.android.ui.medialibrary

import dev.ipf.marmotkit.AttachmentCategoryFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Consumer tests exercise the authoritative native contract; native eligibility remains MDK's responsibility. */
class GroupAttachmentPagingReducerTest {
    /** A 350-message corpus has no recent attachments and includes albums on both sides of the chat cap. */
    @Test
    fun completeSparseHistoryPreservesNativeOrderAndAlbumIndexes() =
        runTest {
            val rows = (349 downTo 0).filter { it < 300 && it % 7 == 0 }.flatMap { listOf(historyEntry(it), historyEntry(it, 4u)) }
            val source = GroupAttachmentFixture(rows)
            val pager = GroupAttachmentPager(source)
            pager.refresh()
            assertEquals(13, pager.state.entries.size)
            while (pager.state.hasMore) pager.loadMore()
            assertEquals(rows.map { it.slotKey() }, pager.state.entries.map { it.slotKey() })
            assertEquals(
                rows.size,
                pager.state.entries
                    .distinctBy { it.slotKey() }
                    .size,
            )
            val calls = source.calls
            pager.loadMore()
            assertEquals(calls, source.calls)
            pager.close()
            assertTrue(source.cursors.all { it.closes == 1 })
            assertTrue(source.versions.all { it.closes == 1 })
        }

    /** Month headers must not regroup interleaved canonical rows by their display timestamps. */
    @Test
    fun displayMonthsNeverReorderCanonicalRows() {
        val rows = listOf(historyEntry(3), historyEntry(2).copy(timelineAt = 1_600_000_000u), historyEntry(1))
        val tiles = attachmentLibraryTiles(rows, null)
        assertEquals(rows.map { it.messageIdHex }, tiles.visualSections.flatMap { it.items }.map { it.messageIdHex })
        assertEquals(
            3,
            tiles.visualSections
                .map { it.sectionKey }
                .distinct()
                .size,
        )
    }

    /** Empty filtered pages still expose a continuation without silently scanning the whole library. */
    @Test
    fun sparseCategoryDoesNotImplyEndOrDrainUnboundedHistory() =
        runTest {
            val rows = (40 downTo 0).map { historyEntry(it, category = if (it == 0) AttachmentCategoryFfi.VIDEO else AttachmentCategoryFfi.FILE) }
            val source = GroupAttachmentFixture(rows)
            val pager = GroupAttachmentPager(source)
            pager.refresh()
            assertTrue(attachmentLibraryTiles(pager.state.entries, null).videos.isEmpty())
            assertTrue(pager.state.hasMore)
            assertEquals(1, source.calls)
            while (pager.state.hasMore) pager.loadMore()
            assertEquals(1, attachmentLibraryTiles(pager.state.entries, null).videos.size)
            pager.close()
        }

    /** Failed continuation retains the same cursor, and retry neither skips nor duplicates a slot. */
    @Test
    fun continuationFailureRetriesSameCursor() =
        runTest {
            val source = GroupAttachmentFixture((30 downTo 0).map { historyEntry(it) })
            val pager = GroupAttachmentPager(source)
            pager.refresh()
            source.failNext = true
            pager.loadMore()
            assertTrue(pager.state.failed)
            assertEquals(13, pager.state.entries.size)
            pager.loadMore()
            assertEquals(26, pager.state.entries.size)
            assertFalse(pager.state.failed)
            pager.close()
        }

    /** Additions above a passed boundary appear while the previously loaded tail remains reachable. */
    @Test
    fun additionsRefreshLoadedRangeWithoutLosingOlderRows() =
        runTest {
            val source = GroupAttachmentFixture((40 downTo 0).map { historyEntry(it) })
            val pager = GroupAttachmentPager(source)
            pager.refresh()
            pager.loadMore()
            val old = pager.state.entries
            source.rows = listOf(historyEntry(99), historyEntry(98)) + source.rows
            source.additions++
            pager.refresh()
            assertEquals(
                "message-99",
                pager.state.entries
                    .first()
                    .messageIdHex,
            )
            assertTrue(pager.state.entries.containsAll(old))
            assertEquals(
                pager.state.entries.size,
                pager.state.entries
                    .distinctBy { it.slotKey() }
                    .size,
            )
            pager.close()
        }

    /** Deletion/invalidation clears old rows before the replacement read, including the former tail. */
    @Test
    fun destructiveRefreshRemovesLoadedRowsWithoutDrainingHistory() =
        runTest {
            val source = GroupAttachmentFixture((300 downTo 0).map { historyEntry(it) })
            val pager = GroupAttachmentPager(source)
            pager.refresh()
            pager.loadMore()
            val removed = pager.state.entries.last()
            source.rows = source.rows.filterNot { it == removed || it.messageIdHex == "message-300" }
            source.removals++
            source.beforePage = { if (pager.state.entries.isEmpty()) assertTrue(pager.state.loading) }
            pager.refresh()
            assertFalse(pager.state.entries.contains(removed))
            assertFalse(pager.state.entries.any { it.messageIdHex == "message-300" })
            assertEquals(4, source.calls)
            pager.close()
        }

    /** A tail removed after an additions probe restores a bounded range, including after a failed head read. */
    @Test
    fun deletionBetweenAdditionsProbeAndHeadReadKeepsRestorationBounded() =
        runTest {
            for (retry in listOf("none", "loadMore", "refresh")) {
                val source = GroupAttachmentFixture((300 downTo 0).map { historyEntry(it) })
                val pager = GroupAttachmentPager(source)
                pager.refresh()
                pager.loadMore()
                val oldTail = pager.state.entries.last()
                source.rows = listOf(historyEntry(999)) + source.rows
                source.additions++
                source.beforePage = {
                    source.beforePage = {}
                    source.rows = source.rows.filterNot { it == oldTail }
                    source.removals++
                    source.failNext = retry != "none"
                }
                pager.refresh()
                if (retry != "none") {
                    assertTrue(pager.state.failed)
                    if (retry == "loadMore") pager.loadMore() else pager.refresh()
                }
                assertEquals(26, pager.state.entries.size)
                assertEquals(source.rows.take(26), pager.state.entries)
                assertTrue(pager.state.hasMore)
                assertEquals(if (retry != "none") 5 else 4, source.calls)
                val calls = source.calls
                pager.refresh()
                assertEquals(calls, source.calls)
                pager.close()
                assertTrue(source.cursors.all { it.closes == 1 })
                assertTrue(source.versions.all { it.closes == 1 })
            }
        }

    /** A deletion racing continuation must replace, rather than append to, the obsolete collection. */
    @Test
    fun typedStaleCursorRestartsAtHead() =
        runTest {
            val source = GroupAttachmentFixture((30 downTo 0).map { historyEntry(it) })
            val pager = GroupAttachmentPager(source)
            pager.refresh()
            source.rows = source.rows.drop(2)
            source.removals++
            pager.loadMore()
            assertEquals(source.rows.take(13), pager.state.entries)
            pager.close()
        }

    /** The initial baseline survives later pages and still detects additions after exhaustion. */
    @Test
    fun laterPageVersionDoesNotHideAnAddition() =
        runTest {
            val source = GroupAttachmentFixture((20 downTo 0).map { historyEntry(it) })
            val pager = GroupAttachmentPager(source)
            pager.refresh()
            source.rows = listOf(historyEntry(99)) + source.rows
            source.additions++
            pager.loadMore()
            assertFalse(pager.state.hasMore)
            pager.refresh()
            assertEquals(source.rows, pager.state.entries)
            pager.close()
        }

    /** Empty history remains refreshable and a failed exhausted-version probe can be retried. */
    @Test
    fun emptyAndExhaustedRefreshRetry() =
        runTest {
            val source = GroupAttachmentFixture(emptyList())
            val pager = GroupAttachmentPager(source)
            pager.refresh()
            assertTrue(pager.state.initialized)
            source.rows = listOf(historyEntry(1))
            source.additions++
            source.failVersion = true
            pager.refresh()
            assertTrue(pager.state.failed)
            pager.loadMore()
            assertEquals(source.rows, pager.state.entries)
            pager.close()
        }
}
