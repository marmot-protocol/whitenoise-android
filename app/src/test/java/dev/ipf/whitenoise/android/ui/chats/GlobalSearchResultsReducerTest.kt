package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.core.SnippetHighlight
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Generations, cursor pages, bounded local-hide refill and stale-page rejection of global message results. */
class GlobalSearchResultsReducerTest {
    /** A restart opens the next generation and drops rows, cursor, request and failure. */
    @Test
    fun restartOpensNextGenerationAndDropsEverything() {
        val (loading, request) = begin(GlobalSearchResultsReducer.restart(GlobalSearchResultsState()))
        val firstBatch = batch(listOf(result("g1", "m1")), cursor(1))
        val loaded = GlobalSearchResultsReducer.applyBatch(loading, request, firstBatch)
        val restarted = GlobalSearchResultsReducer.restart(loaded)

        assertEquals(loaded.generation + 1, restarted.generation)
        assertTrue(restarted.results.isEmpty() && restarted.seenKeys.isEmpty())
        assertNull(restarted.nextCursor)
        assertNull(restarted.inFlight)
    }

    /** A page that completes after the query or filters changed cannot reach the new generation. */
    @Test
    fun staleBatchFromAnOlderQueryIsIgnored() {
        val (_, oldRequest) = begin(GlobalSearchResultsReducer.restart(GlobalSearchResultsState()))
        val (current, newRequest) = begin(GlobalSearchResultsReducer.restart(GlobalSearchResultsState(generation = 1)))

        val afterStale = GlobalSearchResultsReducer.applyBatch(current, oldRequest, batch(listOf(result("g", "old"))))
        assertSame(current, afterStale)
        val afterStaleFailure = GlobalSearchResultsReducer.applyFailure(current, oldRequest)
        assertSame(current, afterStaleFailure)

        val applied = GlobalSearchResultsReducer.applyBatch(current, newRequest, batch(listOf(result("g", "new"))))
        assertEquals(listOf("g:new"), applied.results.map { it.key })
    }

    /** An account switch restarts the generation, so the previous account's late page and failure are dropped. */
    @Test
    fun accountSwitchRejectsThePreviousAccountsLatePageAndFailure() {
        val (accountA, requestA) = begin(GlobalSearchResultsReducer.restart(GlobalSearchResultsState()))
        val accountB = GlobalSearchResultsReducer.restart(accountA)

        assertSame(accountB, GlobalSearchResultsReducer.applyBatch(accountB, requestA, batch(listOf(result("a", "1")))))
        assertSame(accountB, GlobalSearchResultsReducer.applyFailure(accountB, requestA))
        assertTrue(accountB.results.isEmpty())
        assertFalse(accountB.failed)
    }

    /** A batch appends its rows and hands off the continuation of its last consumed page; null is the end. */
    @Test
    fun batchesHandOffTheirCursorAndANullCursorIsFinal() {
        val (first, firstRequest) = begin(GlobalSearchResultsReducer.restart(GlobalSearchResultsState()))
        val afterFirst =
            GlobalSearchResultsReducer.applyBatch(first, firstRequest, batch(listOf(result("g", "2")), cursor(2)))
        assertEquals(cursor(2), afterFirst.nextCursor)
        assertTrue(afterFirst.hasMore)

        val (second, secondRequest) = begin(afterFirst)
        assertEquals(cursor(2), secondRequest.cursor)
        val afterSecond = GlobalSearchResultsReducer.applyBatch(second, secondRequest, batch(listOf(result("g", "1"))))
        assertTrue(afterSecond.endReached)
        assertNull(GlobalSearchResultsReducer.begin(afterSecond))
        assertEquals(listOf("g:2", "g:1"), afterSecond.results.map { it.key })
    }

    /** One batch at a time: a second begin while one is in flight is refused. */
    @Test
    fun onlyOneBatchIsInFlightPerGeneration() {
        val (loading, _) = begin(GlobalSearchResultsReducer.restart(GlobalSearchResultsState()))
        assertNull(GlobalSearchResultsReducer.begin(loading))
    }

    /** A row seen in an earlier batch, or twice in one batch, is shown once. */
    @Test
    fun duplicatesAcrossAndWithinBatchesAreSuppressed() {
        val (first, firstRequest) = begin(GlobalSearchResultsReducer.restart(GlobalSearchResultsState()))
        val afterFirst =
            GlobalSearchResultsReducer.applyBatch(first, firstRequest, batch(listOf(result("g", "1")), cursor(5)))
        val (second, secondRequest) = begin(afterFirst)
        assertEquals(setOf("g:1"), secondRequest.seenKeys)
        val duplicates = listOf(result("G", "1"), result("g", "2"), result("g", "2"))
        val afterSecond = GlobalSearchResultsReducer.applyBatch(second, secondRequest, batch(duplicates, cursor(3)))

        assertEquals(listOf("g:1", "g:2"), afterSecond.results.map { it.key })
    }

    /** Several matches from one chat stay distinct rows, keyed by group and message id. */
    @Test
    fun multipleMatchesFromOneChatRemainDistinct() {
        val (loading, request) = begin(GlobalSearchResultsReducer.restart(GlobalSearchResultsState()))
        val rows = listOf(result("chat", "a"), result("chat", "b"), result("other", "a"))
        val applied = GlobalSearchResultsReducer.applyBatch(loading, request, batch(rows))

        assertEquals(listOf("chat:a", "chat:b", "other:a"), applied.results.map { it.key })
    }

    /** A failed batch keeps loaded rows and its cursor, and a retry reads the same cursor again. */
    @Test
    fun failureKeepsRowsAndRetryRereadsTheSameCursor() {
        val (first, firstRequest) = begin(GlobalSearchResultsReducer.restart(GlobalSearchResultsState()))
        val loaded =
            GlobalSearchResultsReducer.applyBatch(first, firstRequest, batch(listOf(result("g", "1")), cursor(9)))
        val (second, secondRequest) = begin(loaded)
        val failed = GlobalSearchResultsReducer.applyFailure(second, secondRequest)

        assertTrue(failed.failed)
        assertFalse(failed.autoReads)
        assertEquals(listOf("g:1"), failed.results.map { it.key })
        val (retrying, retry) = begin(failed)
        assertFalse(retrying.failed)
        assertEquals(cursor(9), retry.cursor)
    }

    /** A cancelled batch is forgotten without a failure, so the same cursor stays readable. */
    @Test
    fun cancellationForgetsTheRequestWithoutFailing() {
        val (loading, request) = begin(GlobalSearchResultsReducer.restart(GlobalSearchResultsState()))
        val cancelled = GlobalSearchResultsReducer.cancel(loading, request)

        assertNull(cancelled.inFlight)
        assertFalse(cancelled.failed)
        assertNotNull(GlobalSearchResultsReducer.begin(cancelled))
    }

    /** Batches that show nothing keep paging on their own only a few times, then wait for the user. */
    @Test
    fun emptyContinuationBatchesStopAutomaticPagingAfterTheBound() {
        var state = GlobalSearchResultsReducer.restart(GlobalSearchResultsState())
        repeat(GLOBAL_SEARCH_AUTO_EMPTY_BATCHES) { index ->
            assertTrue(state.autoReads)
            val (loading, request) = begin(state)
            state = GlobalSearchResultsReducer.applyBatch(loading, request, batch(emptyList(), cursor(100L - index)))
        }
        assertTrue(state.hasMore)
        assertFalse(state.autoReads)
        val (loading, request) = begin(state)
        state = GlobalSearchResultsReducer.applyBatch(loading, request, batch(listOf(result("g", "x")), cursor(1)))
        assertEquals(0, state.emptyBatches)
        assertTrue(state.autoReads)
    }

    /** Same-second rows across chats page with no duplicate, gap or reordering at any page edge. */
    @Test
    fun sameSecondPageBoundariesHaveNoDuplicateGapOrReordering() =
        runTest {
            // 39 rows sharing three timestamps across three chats, one message id in three chats at once.
            val corpus =
                (0 until 37).map { index ->
                    Row(group = "g${index % 3}", id = "m%02d".format(index), at = 1_000uL + (index / 15).toULong())
                } + listOf(Row("g1", "m00", 1_000uL), Row("g2", "m00", 1_000uL))
            val store = FakeAccountSearch(corpus)
            val shown = pageEverything(store, GlobalSearchBatchBudget(rawPageSize = 4, visibleTarget = 5))

            assertEquals(store.newestFirst().map { "${it.group}:${it.id}" }, shown)
            assertEquals(shown.size, shown.toSet().size)
        }

    /** A plain query with several matches in one chat and matches in others reaches every match across pages. */
    @Test
    fun plainQueryReachesEveryMatchAcrossChatsAndPages() =
        runTest {
            val corpus =
                (0 until 12).map { Row(group = "busy", id = "b$it", at = 2_000uL + it.toULong()) } +
                    (0 until 6).map { Row(group = "quiet$it", id = "q$it", at = 1_500uL + it.toULong()) }
            val store = FakeAccountSearch(corpus)
            val shown = pageEverything(store, GlobalSearchBatchBudget(rawPageSize = 5, visibleTarget = 4))

            assertEquals(18, shown.size)
            assertEquals(12, shown.count { it.startsWith("busy:") })
            assertEquals(store.newestFirst().map { "${it.group}:${it.id}" }, shown)
        }

    /** Local-hide refill reads at most four pages / 200 rows and hands off the final consumed page's cursor. */
    @Test
    fun localHideRefillIsBoundedAndHandsOffTheFinalCursor() =
        runTest {
            val corpus = (0 until 500).map { Row(group = "g", id = "m%03d".format(it), at = it.toULong()) }
            val store = FakeAccountSearch(corpus)
            val batch = fillGlobalSearchBatch(null, emptySet(), store.source(hidden = { true }))

            assertEquals(GLOBAL_SEARCH_BATCH_PAGES, batch.rawPages)
            assertEquals(GLOBAL_SEARCH_BATCH_ROWS, batch.rawRows)
            assertEquals(GLOBAL_SEARCH_BATCH_PAGES, store.reads)
            assertTrue(batch.results.isEmpty())
            // The 200th newest row is the last consumed page's oldest, and the continuation starts there.
            assertEquals(GlobalSearchCursor(300uL, "m300", "g"), batch.nextCursor)
            assertTrue(store.limits.all { it <= GLOBAL_SEARCH_RAW_PAGE_SIZE.toUInt() })
        }

    /** An all-hidden batch returns no rows with a continuation, and the next batch resumes without duplicates. */
    @Test
    fun allHiddenBatchKeepsItsContinuationWithoutDuplicates() =
        runTest {
            val corpus = (0 until 260).map { Row(group = "g", id = "m%03d".format(it), at = it.toULong()) }
            val store = FakeAccountSearch(corpus)
            val visibleIds = setOf("m010", "m005")
            val source = store.source(hidden = { it.id !in visibleIds })
            val first = fillGlobalSearchBatch(null, emptySet(), source)
            assertTrue(first.results.isEmpty())
            assertNotNull(first.nextCursor)

            val second = fillGlobalSearchBatch(first.nextCursor, emptySet(), source)
            assertEquals(listOf("g:m010", "g:m005"), second.results.map { it.key })
            assertNull(second.nextCursor)
        }

    /** A broad query stops at the visible target instead of spending the whole budget. */
    @Test
    fun broadQueryStopsAtTheVisibleTarget() =
        runTest {
            val corpus = (0 until 300).map { Row(group = "g", id = "m%03d".format(it), at = it.toULong()) }
            val store = FakeAccountSearch(corpus)
            val batch = fillGlobalSearchBatch(null, emptySet(), store.source())

            assertEquals(1, batch.rawPages)
            assertEquals(GLOBAL_SEARCH_RAW_PAGE_SIZE, batch.results.size)
        }

    /** Rows already shown are not admitted again by a later batch. */
    @Test
    fun seenRowsAreNotAdmittedAgain() =
        runTest {
            val corpus = (0 until 3).map { Row(group = "g", id = "m$it", at = it.toULong()) }
            val batch = fillGlobalSearchBatch(null, setOf("g:m2"), FakeAccountSearch(corpus).source())

            assertEquals(listOf("g:m1", "g:m0"), batch.results.map { it.key })
        }

    /** Cancelling a batch mid-read stops it before another page is requested. */
    @Test
    fun cancellationStopsTheBatchBeforeTheNextPage() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var reads = 0
            val source =
                GlobalSearchBatchSource<Row>(
                    readPage = { _, _ ->
                        reads++
                        gate.await()
                        GlobalSearchRawPage(listOf(Row("g", "m$reads", reads.toULong())), hasMoreBefore = true)
                    },
                    cursorOf = { GlobalSearchCursor(it.at, it.id, it.group) },
                    admit = { null },
                )
            val job = async { fillGlobalSearchBatch(null, emptySet(), source) }
            runCurrent()
            job.cancel()
            gate.complete(Unit)
            runCurrent()

            assertTrue(job.isCancelled)
            assertEquals(1, reads)
        }

    /** A refresh replaces the loaded range in place: gone rows drop, a newer match joins at the head. */
    @Test
    fun refreshReplacesTheLoadedRangeInPlace() {
        val (first, firstRequest) = begin(GlobalSearchResultsReducer.restart(GlobalSearchResultsState()))
        val loadedRows = listOf(result("g", "c", at = 30uL), result("g", "b", at = 20uL), result("g", "a", at = 10uL))
        val oldest = loadedRows.last().cursor
        val loaded = GlobalSearchResultsReducer.applyBatch(first, firstRequest, batch(loadedRows, oldest))
        val (refreshing, refresh) = requireNotNull(GlobalSearchResultsReducer.beginRefresh(loaded))
        assertTrue(refresh.refresh)
        assertEquals(oldest, refresh.cursor)

        val reread = listOf(result("g", "new", at = 40uL), result("g", "c", at = 30uL), result("g", "a", at = 10uL))
        val refreshed = GlobalSearchResultsReducer.applyRefresh(refreshing, refresh, reread, oldest)

        assertEquals(listOf("g:new", "g:c", "g:a"), refreshed.results.map { it.key })
        assertEquals(oldest, refreshed.nextCursor)
        assertNull(refreshed.inFlight)
    }

    /** A reread capped before the old continuation keeps the loaded rows below where it stopped. */
    @Test
    fun cappedRefreshKeepsRowsBelowWhereItStopped() {
        val (first, firstRequest) = begin(GlobalSearchResultsReducer.restart(GlobalSearchResultsState()))
        val loadedRows = listOf(result("g", "c", at = 30uL), result("g", "b", at = 20uL), result("g", "a", at = 10uL))
        val loaded = GlobalSearchResultsReducer.applyBatch(first, firstRequest, batch(loadedRows, cursor(5)))
        val (refreshing, refresh) = requireNotNull(GlobalSearchResultsReducer.beginRefresh(loaded))
        val stoppedAt = GlobalSearchCursor(20uL, "b", "g")
        val refreshed = GlobalSearchResultsReducer.applyRefresh(refreshing, refresh, listOf(loadedRows[1]), stoppedAt)

        assertEquals(listOf("g:b", "g:a"), refreshed.results.map { it.key })
    }

    /** Nothing to refresh before the first batch; a refresh from an older generation is ignored. */
    @Test
    fun refreshNeedsLoadedRowsAndAnUnchangedGeneration() {
        val fresh = GlobalSearchResultsReducer.restart(GlobalSearchResultsState())
        assertNull(GlobalSearchResultsReducer.beginRefresh(fresh))
        val (first, firstRequest) = begin(fresh)
        val loaded = GlobalSearchResultsReducer.applyBatch(first, firstRequest, batch(listOf(result("g", "1"))))
        val (_, refresh) = requireNotNull(GlobalSearchResultsReducer.beginRefresh(loaded))
        val restarted = GlobalSearchResultsReducer.restart(loaded)

        assertSame(restarted, GlobalSearchResultsReducer.applyRefresh(restarted, refresh, emptyList(), null))
    }

    /** The earliest disappearing deadline among loaded rows decides when to recheck them. */
    @Test
    fun nextExpiryIsTheEarliestLoadedDeadline() {
        val rows = listOf(result("g", "1", expiresAt = 90uL), result("g", "2"), result("g", "3", expiresAt = 40uL))
        assertEquals(40uL, GlobalSearchResultsState(results = rows).nextExpiry)
        assertNull(GlobalSearchResultsState(results = listOf(result("g", "4"))).nextExpiry)
    }

    /** A reread from the head stops at the old continuation, so it rereads only the loaded range. */
    @Test
    fun rereadStopsAtTheOldContinuation() =
        runTest {
            val corpus = (0 until 300).map { Row(group = "g", id = "m%03d".format(it), at = it.toULong()) }
            val store = FakeAccountSearch(corpus)
            val through = GlobalSearchCursor(250uL, "m250", "g")
            val (rows, stoppedAt) = rereadGlobalSearchRange(store.source(), through, budget = 10)

            assertTrue(stoppedAt != null && stoppedAt <= through)
            assertTrue(rows.map { it.key }.containsAll((251 until 300).map { "g:m%03d".format(it) }))
            assertEquals(1, store.reads)
        }

    /** Pages a fake store to its end through the reducer and returns every shown key in order. */
    private suspend fun pageEverything(
        store: FakeAccountSearch,
        budget: GlobalSearchBatchBudget,
    ): List<String> {
        var state = GlobalSearchResultsReducer.restart(GlobalSearchResultsState())
        while (!state.endReached) {
            val (loading, request) = begin(state)
            val batch = fillGlobalSearchBatch(request.cursor, request.seenKeys, store.source(), budget)
            state = GlobalSearchResultsReducer.applyBatch(loading, request, batch)
        }
        return state.results.map { it.key }
    }

    /** Starts a batch, failing the test if the reducer refuses. */
    private fun begin(state: GlobalSearchResultsState) = requireNotNull(GlobalSearchResultsReducer.begin(state))

    /** A batch of [results] with [next] as its continuation. */
    private fun batch(
        results: List<GlobalSearchResult>,
        next: GlobalSearchCursor? = null,
    ) = GlobalSearchBatch(results, next, rawPages = 1, rawRows = results.size)

    /** A cursor at second [at]. */
    private fun cursor(at: Long) = GlobalSearchCursor(at.toULong(), "m$at", "g")

    /** A minimal result row at second [at], disappearing at [expiresAt] when given. */
    private fun result(
        group: String,
        id: String,
        at: ULong = 1uL,
        expiresAt: ULong? = null,
    ) = GlobalSearchResult(group, id, "sender", at, SnippetHighlight("text", 0, 0), expiresAt = expiresAt)

    /** One stored message of the fake account. */
    private data class Row(
        val group: String,
        val id: String,
        val at: ULong,
    )

    /**
     * MDK's account-wide search over an in-memory corpus: DESC by (time, message id, group), strict cursor, and
     * pages reversed to oldest-first exactly as MDK returns them.
     */
    private class FakeAccountSearch(
        corpus: List<Row>,
    ) {
        private val ordered = corpus.sortedWith(accountSearchOrder())
        var reads = 0
        val limits = mutableListOf<UInt>()

        /** MDK's account search order: time, message id, group, all descending. */
        private fun accountSearchOrder(): Comparator<Row> {
            val byTime = compareByDescending<Row> { it.at }
            return byTime.thenByDescending { it.id }.thenByDescending { it.group }
        }

        /** The full corpus newest first, the order the results must reproduce. */
        fun newestFirst(): List<Row> = ordered

        /** One page after [cursor], at most [limit] rows, oldest first. */
        fun page(
            cursor: GlobalSearchCursor?,
            limit: UInt,
        ): GlobalSearchRawPage<Row> {
            reads++
            limits += limit
            val remaining = ordered.filter { cursor == null || GlobalSearchCursor(it.at, it.id, it.group) < cursor }
            val page = remaining.take(limit.toInt())
            return GlobalSearchRawPage(page.reversed(), hasMoreBefore = remaining.size > page.size)
        }

        /** A batch source over this store; [hidden] rows are dropped the way local hides are. */
        fun source(hidden: (Row) -> Boolean = { false }) =
            GlobalSearchBatchSource<Row>(
                readPage = { cursor, limit -> page(cursor, limit) },
                cursorOf = { GlobalSearchCursor(it.at, it.id, it.group) },
                admit = { row ->
                    val snippet = SnippetHighlight("t", 0, 0)
                    if (hidden(row)) null else GlobalSearchResult(row.group, row.id, "s", row.at, snippet)
                },
            )
    }
}
