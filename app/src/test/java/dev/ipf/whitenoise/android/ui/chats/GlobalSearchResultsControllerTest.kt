package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.core.MessageSearchConstraints
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.search.GlobalSearchEpochBounds
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.ACCOUNT_ORDER
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.FakeReader
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.OTHER_SENDER
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.SENDER
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.StoreReader
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.chosen
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.controller
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.keys
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.page
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchTestFixtures.record
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The controller drives MDK's typed account-wide search through the `timelineMessages` seam (#1944). */
class GlobalSearchResultsControllerTest {
    /** The first page carries every typed filter MDK can apply and wall-clock account order, with no cursor. */
    @Test
    fun firstPageCarriesTheTypedFiltersAndNoCursor() =
        runTest {
            val reader = FakeReader({ page(record("g1", "m1", at = 50uL, text = "gm everyone")) })
            val bounds = GlobalSearchEpochBounds(startEpochMillisInclusive = 1_500L, endEpochMillisExclusive = 9_001L)
            val query =
                GlobalSearchResultsQuery(
                    needle = "gm",
                    groups = setOf("g1"),
                    constraints = MessageSearchConstraints(senderIds = setOf(SENDER), dateBounds = bounds),
                )
            controller(reader).search(query)
            advanceUntilIdle()

            val sent = reader.queries.single()
            assertEquals("g1", sent.groupIdHex)
            assertEquals("gm", sent.search)
            assertEquals(SENDER, sent.sender)
            assertEquals(2uL, sent.since)
            assertEquals(10uL, sent.until)
            assertEquals(0u, sent.attachmentType)
            assertTrue(sent.searchWallClock)
            assertNull(sent.before)
            assertNull(sent.beforeMessageId)
            assertNull(sent.cursorGroupId)
            assertEquals(GLOBAL_SEARCH_RAW_PAGE_SIZE.toUInt(), sent.limit)
        }

    /** The next page's cursor is the previous page's oldest row: its time, message id and group. */
    @Test
    fun nextPageCursorIsTheOldestRowsTimeIdAndGroup() =
        runTest {
            val first = (0 until 50).map { record("g${it % 2}", "m%02d".format(it), at = 100uL + it.toULong()) }
            val reader =
                FakeReader(
                    { TimelinePageFfi(first, hasMoreBefore = true, hasMoreAfter = false) },
                    { page(record("g9", "older", at = 10uL)) },
                )
            val controller = controller(reader)
            controller.search(GlobalSearchResultsQuery(needle = "hello"))
            advanceUntilIdle()
            controller.loadMore()
            advanceUntilIdle()

            val second = reader.queries[1]
            assertNull(second.groupIdHex)
            assertEquals(100uL, second.before)
            assertEquals("m00", second.beforeMessageId)
            assertEquals("g0", second.cursorGroupId)
            assertEquals(51, controller.state.results.size)
            assertTrue(controller.state.endReached)
        }

    /** Several senders, or a scope too large to read chat by chat, search account-wide and narrow on the host. */
    @Test
    fun widerFiltersSearchAccountWideAndNarrowOnTheHost() =
        runTest {
            val reader =
                FakeReader({
                    page(
                        record("chosen", "a", at = 9uL, sender = SENDER),
                        record("other", "b", at = 8uL, sender = SENDER),
                        record("chosen", "c", at = 7uL, sender = "ff".repeat(32)),
                        record("chosen2", "d", at = 6uL, sender = OTHER_SENDER),
                    )
                })
            val query =
                GlobalSearchResultsQuery(
                    needle = "hello",
                    // More chats than the chat-by-chat fan-out reads, so one account-wide query covers them.
                    groups = setOf("chosen", "chosen2") + (1..GLOBAL_SEARCH_FAN_OUT_MAX_CHATS).map { "spare$it" },
                    constraints = MessageSearchConstraints(senderIds = setOf(SENDER, OTHER_SENDER)),
                )
            val controller = controller(reader)
            controller.search(query)
            advanceUntilIdle()

            val sent = reader.queries.single()
            assertNull(sent.groupIdHex)
            assertNull(sent.sender)
            assertEquals(listOf("chosen:a", "chosen2:d"), controller.state.results.map { it.key })
        }

    /** Text and link content needs no attachment, so MDK is not narrowed by attachment type. */
    @Test
    fun textContentFiltersDoNotNarrowTheAttachmentType() =
        runTest {
            val reader = FakeReader({ page() })
            val constraints = MessageSearchConstraints(contentKinds = setOf(GlobalSearchContentKind.LINKS))
            controller(reader).search(GlobalSearchResultsQuery(needle = "", constraints = constraints))
            advanceUntilIdle()

            assertEquals(0u, reader.queries.single().attachmentType)
            assertNull(reader.queries.single().search)
        }

    /** Hidden, deleted, expired, system and never-accepted rows never become results or show their text. */
    @Test
    fun hiddenDeletedExpiredAndSystemRowsNeverShow() =
        runTest {
            val reader =
                FakeReader({
                    page(
                        record("g", "visible", at = 9uL, text = "secret visible"),
                        record("g", "hidden", at = 8uL, text = "secret hidden"),
                        record("g", "deleted", at = 7uL, text = "secret deleted", deleted = true),
                        record("g", "expired", at = 6uL, text = "secret expired", expiresAt = 1uL),
                        record("g", "system", at = 5uL, text = "{\"secret\":true}", kind = 1210uL),
                        record("g", "reaction", at = 4uL, text = "secret", kind = 7uL),
                        record("g", "losing", at = 3uL, text = "secret losing branch", invalidation = "LosingBranch"),
                    )
                })
            val controller =
                controller(reader, hidden = { group -> if (group == "g") setOf("HIDDEN") else emptySet() })
            controller.search(GlobalSearchResultsQuery(needle = "secret"))
            advanceUntilIdle()

            assertEquals(listOf("g:visible"), controller.state.results.map { it.key })
            val snippet =
                controller.state.results
                    .single()
                    .snippet
            assertEquals("secret visible", snippet.text)
            assertEquals(0 to 6, snippet.highlightStart to snippet.highlightEnd)
        }

    /** A new query cancels the batch in flight; the old query's late page never appears. */
    @Test
    fun newQueryCancelsTheBatchInFlightAndRejectsItsLatePage() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val reader =
                FakeReader(
                    {
                        gate.await()
                        page(record("g", "old", at = 2uL, text = "alpha"))
                    },
                    { page(record("g", "new", at = 1uL, text = "beta")) },
                )
            val controller = controller(reader)
            controller.search(GlobalSearchResultsQuery(needle = "alpha"))
            advanceUntilIdle()
            controller.search(GlobalSearchResultsQuery(needle = "beta"))
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("g:new"), controller.state.results.map { it.key })
            assertEquals(2, controller.state.generation)
        }

    /** An account or runtime change fences a completed read, so it never publishes. */
    @Test
    fun ownerChangeRejectsACompletedRead() =
        runTest {
            var current = true
            val reader =
                FakeReader({
                    current = false
                    page(record("g", "late", at = 1uL))
                })
            val controller = controller(reader, isCurrent = { current })
            controller.search(GlobalSearchResultsQuery(needle = "hello"))
            advanceUntilIdle()

            assertTrue(controller.state.results.isEmpty())
            assertFalse(controller.state.failed)
        }

    /** A failed later page keeps loaded results; retry rereads from the same cursor and continues. */
    @Test
    fun failedPageKeepsResultsAndRetryContinuesFromItsCursor() =
        runTest {
            val first = (0 until 50).map { record("g", "m%02d".format(it), at = 100uL + it.toULong()) }
            val reader =
                FakeReader(
                    { TimelinePageFfi(first, hasMoreBefore = true, hasMoreAfter = false) },
                    { error("relay-free local read failed") },
                    { page(record("g", "older", at = 1uL)) },
                )
            val controller = controller(reader)
            controller.search(GlobalSearchResultsQuery(needle = "hello"))
            advanceUntilIdle()
            controller.loadMore()
            advanceUntilIdle()
            assertTrue(controller.state.failed)
            assertEquals(50, controller.state.results.size)

            controller.retry()
            advanceUntilIdle()
            assertFalse(controller.state.failed)
            assertEquals(51, controller.state.results.size)
            assertEquals(reader.queries[1].before, reader.queries[2].before)
            assertEquals(reader.queries[1].beforeMessageId, reader.queries[2].beforeMessageId)
        }

    /** A scope with no chats ends at once without reading MDK. */
    @Test
    fun emptyScopeEndsWithoutAnyRead() =
        runTest {
            val reader = FakeReader()
            val controller = controller(reader)
            controller.search(GlobalSearchResultsQuery(needle = "hello", groups = emptySet()))
            advanceUntilIdle()

            assertTrue(reader.queries.isEmpty())
            assertTrue(controller.state.endReached)
            assertFalse(controller.state.loading)
        }

    /** A returning session rereads until it holds as many rows as before. */
    @Test
    fun resumeRereadsUpToThePreviousExtent() =
        runTest {
            val first = (0 until 50).map { record("g", "m%02d".format(it), at = 100uL + it.toULong()) }
            val reader =
                FakeReader(
                    { TimelinePageFfi(first, hasMoreBefore = true, hasMoreAfter = false) },
                    { page(record("g", "older", at = 1uL)) },
                )
            val controller = controller(reader)
            controller.search(GlobalSearchResultsQuery(needle = "hello"), resumeTarget = 51)
            advanceUntilIdle()

            assertEquals(2, reader.queries.size)
            assertEquals(51, controller.state.results.size)
            assertFalse(controller.state.resuming)
        }

    /** Repeating the current query does not restart its generation. */
    @Test
    fun repeatingTheCurrentQueryKeepsItsGeneration() =
        runTest {
            val reader = FakeReader({ page(record("g", "m", at = 1uL)) })
            val controller = controller(reader)
            val query = GlobalSearchResultsQuery(needle = "hello")
            controller.search(query)
            advanceUntilIdle()
            controller.search(query.copy())
            advanceUntilIdle()

            assertEquals(1, controller.state.generation)
            assertEquals(1, reader.queries.size)
        }

    /** MDK's case folding is trusted: a dotted capital İ that MDK matched is not dropped by a host re-check. */
    @Test
    fun mdkNeedleMatchIsNotDroppedByHostCaseFolding() =
        runTest {
            val reader = FakeReader({ page(record("g", "trip", at = 5uL, text = "İstanbul trip")) })
            val controller = controller(reader)
            controller.search(GlobalSearchResultsQuery(needle = "İstanbul"))
            advanceUntilIdle()

            assertEquals("İstanbul", reader.queries.single().search)
            assertEquals(listOf("g:trip"), controller.state.results.map { it.key })
        }

    /** Two scoped chats buried under 600 newer matches elsewhere are read chat by chat and found at once. */
    @Test
    fun smallScopeReadsEachChatAndFindsBuriedMatches() =
        runTest {
            val noise = (0 until 600).map { record("noise${it % 30}", "n$it", at = 10_000uL + it.toULong()) }
            val scoped = (0 until 6).map { record(chosen(it), "s$it", at = 100uL + it.toULong()) }
            val store = StoreReader(noise + scoped)
            val controller = controller(store)
            controller.search(GlobalSearchResultsQuery(needle = "hello", groups = setOf("chosen-a", "chosen-b")))
            advanceUntilIdle()

            assertEquals((5 downTo 0).map { "${chosen(it)}:s$it" }, keys(controller))
            assertTrue(controller.state.endReached)
            assertTrue(store.queries.all { it.groupIdHex in setOf("chosen-a", "chosen-b") && it.searchWallClock })
            assertTrue(store.rowsReturned <= GLOBAL_SEARCH_BATCH_ROWS)
        }

    /** A merged chat-by-chat read pages in account order across chats with no duplicate or gap. */
    @Test
    fun smallScopeMergesChatsInAccountOrderAcrossPages() =
        runTest {
            val rows = (0 until 120).map { record("c${it % 3}", "m%03d".format(it), at = 500uL + (it / 4).toULong()) }
            val store = StoreReader(rows + (0 until 40).map { record("other", "o$it", at = 900uL + it.toULong()) })
            val controller = controller(store)
            controller.search(GlobalSearchResultsQuery(needle = "hello", groups = setOf("c0", "c1", "c2")))
            advanceUntilIdle()
            while (!controller.state.endReached) {
                controller.loadMore()
                advanceUntilIdle()
            }

            val expected = rows.sortedWith(ACCOUNT_ORDER).map { "${it.groupIdHex}:${it.messageIdHex}" }
            assertEquals(expected, keys(controller))
        }

    /** A return whose rows were all deleted meanwhile reads a bounded number of pages and shows what it found. */
    @Test
    fun resumeAfterRowsVanishedIsBounded() =
        runTest {
            val reactions = (0 until 2_000).map { record("g", "r%04d".format(it), at = it.toULong(), kind = 7uL) }
            val store = StoreReader(reactions)
            val controller = controller(store)
            controller.search(GlobalSearchResultsQuery(needle = "hello"), resumeTarget = 300)
            advanceUntilIdle()

            assertFalse(controller.state.resuming)
            assertTrue(controller.state.results.isEmpty())
            assertEquals(GLOBAL_SEARCH_AUTO_EMPTY_BATCHES * GLOBAL_SEARCH_BATCH_PAGES, store.queries.size)
        }

    /** The resume extent belongs to one account and runtime; another owner starts at the top. */
    @Test
    fun resumeExtentIsKeyedByOwner() {
        val extent = GlobalSearchResultsExtent()
        val query = GlobalSearchResultsQuery(needle = "gm")
        extent.record("account-a" to 1, query, loaded = 40)

        assertEquals(40, extent.resumeCount("account-a" to 1, query))
        assertEquals(0, extent.resumeCount("account-b" to 1, query))
        assertEquals(0, extent.resumeCount("account-a" to 2, query))
        assertEquals(0, extent.resumeCount("account-a" to 1, query.copy(needle = "other")))
    }

    /** A same-second, same-id twin in another scoped chat at the merged page edge is listed, not skipped. */
    @Test
    fun smallScopeTakesATwinRowAtThePageEdge() =
        runTest {
            // Chat b fills its 25-row share exactly down to (100, "x"); chat a holds the twin (100, "x").
            val chatB =
                (1..24).map { record("b", "b%02d".format(it), at = 100uL + it.toULong()) } +
                    record("b", "x", at = 100uL) +
                    (1..30).map { record("b", "old%02d".format(it), at = 50uL - it.toULong()) }
            val chatA = listOf(record("a", "x", at = 100uL), record("a", "a1", at = 99uL), record("a", "a2", at = 98uL))
            val store = StoreReader(chatB + chatA)
            val controller = controller(store)
            controller.search(GlobalSearchResultsQuery(needle = "hello", groups = setOf("a", "b")))
            advanceUntilIdle()
            while (!controller.state.endReached) {
                controller.loadMore()
                advanceUntilIdle()
            }

            val expected = (chatB + chatA).sortedWith(ACCOUNT_ORDER).map { "${it.groupIdHex}:${it.messageIdHex}" }
            assertEquals(expected, keys(controller))
            assertTrue("a:x" in keys(controller))
        }
}
