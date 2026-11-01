@file:Suppress("MaxLineLength")

package dev.ipf.whitenoise.android.ui.medialibrary

import dev.ipf.marmotkit.AccountAttachmentEntryFfi
import dev.ipf.marmotkit.AttachmentCategoryFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The account library pager against MDK's paging contract; eligibility itself stays MDK's (#2000). */
class AccountAttachmentPagerTest {
    private val everything: (AccountAttachmentEntryFfi) -> Boolean = { true }

    /** Paging keeps native order across chats, and the same message ID in two chats stays two items. */
    @Test
    fun pagesNativeOrderAcrossChatsWithoutDuplicates() =
        runTest {
            val rows = (299 downTo 0).map { accountEntry(it, group = if (it % 2 == 0) "aa" else "bb") } + accountEntry(5, group = "aa")
            val source = AccountAttachmentFixture(rows.distinctBy { it.slotKey() }, size = 40)
            val pager = AccountAttachmentPager(source, everything)

            pager.refresh()
            while (pager.state.hasMore) pager.loadMore()

            assertEquals(source.rows.map { it.slotKey() }, pager.state.entries.map { it.slotKey() })
            pager.close()
            assertTrue(source.cursors.all { it.closes == 1 })
            assertTrue(source.versions.all { it.closes == 1 })
        }

    /** A selective filter reads at most the batch's page budget and keeps the continuation, never draining. */
    @Test
    fun selectiveFilterReadsABoundedBatchAndKeepsTheContinuation() =
        runTest {
            val rows = (999 downTo 0).map { accountEntry(it, category = if (it == 0) AttachmentCategoryFfi.VIDEO else AttachmentCategoryFfi.FILE) }
            val source = AccountAttachmentFixture(rows)
            val pager = AccountAttachmentPager(source, { it.entry.category == AttachmentCategoryFfi.VIDEO })

            pager.refresh()

            assertEquals(ACCOUNT_ATTACHMENT_BATCH_PAGES, source.calls)
            assertTrue(pager.state.entries.isEmpty())
            assertTrue(pager.state.hasMore)
            assertFalse(pager.state.loading)

            pager.loadMore()
            assertEquals(2 * ACCOUNT_ATTACHMENT_BATCH_PAGES, source.calls)
            while (pager.state.hasMore) pager.loadMore()
            assertEquals(listOf("message-0"), pager.state.entries.map { it.entry.messageIdHex })
            pager.close()
        }

    /** A broad view stops after the first page once it admitted enough rows. */
    @Test
    fun broadViewStopsAtTheFirstSufficientPage() =
        runTest {
            val source = AccountAttachmentFixture((499 downTo 0).map { accountEntry(it) })
            val pager = AccountAttachmentPager(source, everything)

            pager.refresh()

            assertEquals(1, source.calls)
            assertEquals(100, pager.state.entries.size)
            pager.close()
        }

    /** Additions reread from the head as far as the old tail, with the new row first and no duplicates. */
    @Test
    fun additionsReloadFromTheHeadThroughThePreviousTail() =
        runTest {
            val source = AccountAttachmentFixture((249 downTo 0).map { accountEntry(it) })
            val pager = AccountAttachmentPager(source, everything)
            pager.refresh()
            pager.loadMore()
            val tail =
                pager.state.entries
                    .last()
                    .slotKey()

            source.rows = listOf(accountEntry(1_000)) + source.rows
            source.additions++
            pager.refresh()

            assertEquals(
                "message-1000",
                pager.state.entries
                    .first()
                    .entry.messageIdHex,
            )
            assertTrue(pager.state.entries.any { it.slotKey() == tail })
            assertEquals(
                pager.state.entries.size,
                pager.state.entries
                    .distinctBy { it.slotKey() }
                    .size,
            )
            pager.close()
        }

    /** A destructive change discards every loaded row, so a removed attachment cannot linger. */
    @Test
    fun restartRequiredDiscardsRemovedRows() =
        runTest {
            val source = AccountAttachmentFixture((9 downTo 0).map { accountEntry(it) })
            val pager = AccountAttachmentPager(source, everything)
            pager.refresh()

            source.rows = source.rows.filterNot { it.entry.messageIdHex == "message-4" }
            source.removals++
            pager.refresh()

            assertFalse(pager.state.entries.any { it.entry.messageIdHex == "message-4" })
            assertEquals(9, pager.state.entries.size)
            pager.close()
        }

    /** A cursor invalidated mid-crawl restarts from the head instead of appending old pages. */
    @Test
    fun staleCursorRestartsFromTheHead() =
        runTest {
            val source = AccountAttachmentFixture((249 downTo 0).map { accountEntry(it) })
            val pager = AccountAttachmentPager(source, everything)
            pager.refresh()

            source.rows = source.rows.drop(1)
            source.removals++
            pager.loadMore()

            assertEquals(
                "message-248",
                pager.state.entries
                    .first()
                    .entry.messageIdHex,
            )
            assertEquals(
                pager.state.entries.size,
                pager.state.entries
                    .distinctBy { it.slotKey() }
                    .size,
            )
            pager.close()
        }

    /** A failed continuation keeps its rows and cursor; retrying neither skips nor repeats a slot. */
    @Test
    fun failedContinuationRetriesTheSameCursor() =
        runTest {
            val source = AccountAttachmentFixture((249 downTo 0).map { accountEntry(it) })
            val pager = AccountAttachmentPager(source, everything)
            pager.refresh()

            source.failNext = true
            pager.loadMore()
            assertEquals(AccountAttachmentFailure.READ, pager.state.failure)
            assertEquals(100, pager.state.entries.size)

            pager.loadMore()
            assertNull(pager.state.failure)
            assertEquals((249 downTo 50).map { "message-$it" }, pager.state.entries.map { it.entry.messageIdHex })
            pager.close()
        }

    /** MDK refusing the filters is reported apart from a read failure, since retrying cannot help. */
    @Test
    fun invalidQueryIsReportedSeparately() =
        runTest {
            val source = AccountAttachmentFixture(listOf(accountEntry(1))).apply { invalidQuery = true }
            val pager = AccountAttachmentPager(source, everything)

            pager.refresh()

            assertEquals(AccountAttachmentFailure.QUERY, pager.state.failure)
            assertTrue(pager.state.entries.isEmpty())
            pager.close()
        }

    /** The earliest retention deadline of any loaded page is the one the screen schedules. */
    @Test
    fun keepsTheEarliestExpiry() =
        runTest {
            val source = AccountAttachmentFixture((249 downTo 0).map { accountEntry(it) }).apply { expiries = listOf(900u, 500u, null) }
            val pager = AccountAttachmentPager(source, everything)

            pager.refresh()
            assertEquals(900uL, pager.state.nextExpiry)
            pager.loadMore()
            assertEquals(500uL, pager.state.nextExpiry)
            pager.loadMore()
            assertEquals(500uL, pager.state.nextExpiry)
            pager.close()
        }

    /** A closed or superseded owner ignores late reads and keeps no rows. */
    @Test
    fun closedOwnerIgnoresLaterReads() =
        runTest {
            var current = true
            val source = AccountAttachmentFixture((9 downTo 0).map { accountEntry(it) })
            val pager = AccountAttachmentPager(source, everything) { current }
            pager.refresh()

            current = false
            pager.loadMore()
            pager.close()

            assertTrue(pager.state.entries.isEmpty())
            assertEquals(1, source.calls)
        }
}
