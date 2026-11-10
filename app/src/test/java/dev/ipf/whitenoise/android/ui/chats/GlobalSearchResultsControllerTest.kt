package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.DeletionSourceFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.whitenoise.android.core.MessageSearchConstraints
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.search.GlobalSearchEpochBounds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

    /** A refresh drops a row deleted while listed, adds a newer match, and keeps the other rows in place. */
    @Test
    fun refreshDropsDeletedRowsAndAddsNewMatches() =
        runTest {
            val store = StoreReader((0 until 5).map { record("g", "m$it", at = 100uL + it.toULong()) })
            val controller = controller(store)
            controller.search(GlobalSearchResultsQuery(needle = "hello"))
            advanceUntilIdle()
            assertEquals(listOf("g:m4", "g:m3", "g:m2", "g:m1", "g:m0"), keys(controller))

            store.replace("m2") { it.copy(deleted = true) }
            store.add(record("g", "fresh", at = 200uL))
            controller.refresh()
            advanceUntilIdle()

            assertEquals(listOf("g:fresh", "g:m4", "g:m3", "g:m1", "g:m0"), keys(controller))
            assertFalse(controller.state.failed)
        }

    /** A refresh asked for while a later page is loading runs once that page lands. */
    @Test
    fun refreshDuringALoadingPageRunsAfterIt() =
        runTest {
            val store = StoreReader((0 until 60).map { record("g", "m%02d".format(it), at = 100uL + it.toULong()) })
            val controller = controller(store)
            controller.search(GlobalSearchResultsQuery(needle = "hello"))
            advanceUntilIdle()
            store.replace("m59") { it.copy(deleted = true) }
            controller.loadMore()
            controller.refresh()
            advanceUntilIdle()

            assertFalse(controller.state.results.any { it.messageIdHex == "m59" })
            assertEquals(59, controller.state.results.size)
        }

    /** A failed background refresh keeps every row and shows no failure boundary. */
    @Test
    fun failedRefreshKeepsRowsWithoutAFailure() =
        runTest {
            val first = page(record("g", "a", at = 2uL), record("g", "b", at = 1uL))
            val reader = FakeReader({ first }, { error("local read failed") })
            val controller = controller(reader)
            controller.search(GlobalSearchResultsQuery(needle = "hello"))
            advanceUntilIdle()
            controller.refresh()
            advanceUntilIdle()

            assertEquals(listOf("g:a", "g:b"), keys(controller))
            assertFalse(controller.state.failed)
            assertFalse(controller.state.loading)
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

    /** One of the two scoped chats, alternating by index. */
    private fun chosen(index: Int) = if (index % 2 == 0) "chosen-a" else "chosen-b"

    /** The keys of the loaded results in display order. */
    private fun keys(controller: GlobalSearchResultsController) = controller.state.results.map { it.key }

    /** A controller whose batches run as children of the test, with the given local hides and owner check. */
    private fun TestScope.controller(
        reader: GlobalSearchTimelineReader,
        hidden: (String) -> Set<String> = { emptySet() },
        isCurrent: () -> Boolean = { true },
    ) = GlobalSearchResultsController(
        reader = reader,
        scope = this,
        environment =
            GlobalSearchResultsEnvironment(
                hiddenMessageIds = hidden,
                isCurrent = isCurrent,
                nowMillis = { NOW_MILLIS },
                ioDispatcher = StandardTestDispatcher(testScheduler),
            ),
    )

    /**
     * An in-memory MDK store: filters by chat, text and sender, orders account-wide by (time, message id, group)
     * or within one chat by (time, message id), applies the strict cursor, and returns pages oldest first.
     */
    private class StoreReader(
        records: List<TimelineMessageRecordFfi>,
    ) : GlobalSearchTimelineReader {
        private val rows = records.toMutableList()
        val queries = mutableListOf<TimelineMessageQueryFfi>()
        var rowsReturned = 0

        /** Changes the stored row with [id]. */
        fun replace(
            id: String,
            change: (TimelineMessageRecordFfi) -> TimelineMessageRecordFfi,
        ) {
            val index = rows.indexOfFirst { it.messageIdHex == id }
            rows[index] = change(rows[index])
        }

        /** Stores a new row. */
        fun add(record: TimelineMessageRecordFfi) {
            rows += record
        }

        /** Answers one typed query the way MDK's account-wide or single-chat wall-clock search does. */
        override suspend fun page(query: TimelineMessageQueryFfi): TimelinePageFfi {
            queries += query
            val needle = query.search?.trim()?.lowercase()
            val cursor =
                query.before?.let { at ->
                    GlobalSearchCursor(at, requireNotNull(query.beforeMessageId), query.cursorGroupId.orEmpty())
                }
            val matching =
                rows
                    .filter { query.groupIdHex == null || it.groupIdHex == query.groupIdHex }
                    .filter { needle == null || it.plaintext.lowercase().contains(needle) }
                    .filter { query.sender == null || it.sender == query.sender }
                    .filter { cursor == null || it.isOlderThan(cursor, sameChat = query.groupIdHex != null) }
                    .sortedWith(ACCOUNT_ORDER)
            val limit = query.limit!!.toInt()
            val page = matching.take(limit)
            rowsReturned += page.size
            return TimelinePageFfi(page.reversed(), hasMoreBefore = matching.size > limit, hasMoreAfter = false)
        }

        /** MDK's strict cursor predicate: by time and id in one chat, by time, id and group account-wide. */
        private fun TimelineMessageRecordFfi.isOlderThan(
            cursor: GlobalSearchCursor,
            sameChat: Boolean,
        ): Boolean {
            val own = if (sameChat) GlobalSearchCursor(timelineAt, messageIdHex, "") else globalSearchCursor()
            val bound = if (sameChat) cursor.copy(groupIdHex = "") else cursor
            return own < bound
        }
    }

    /** One final page holding [records] in the order given. */
    private fun page(vararg records: TimelineMessageRecordFfi) =
        TimelinePageFfi(records.toList().sortedBy { it.timelineAt }, hasMoreBefore = false, hasMoreAfter = false)

    /** Records each typed query and answers with the next scripted page. */
    private class FakeReader(
        vararg pages: suspend () -> TimelinePageFfi,
    ) : GlobalSearchTimelineReader {
        private val scripted = ArrayDeque(pages.toList())
        val queries = mutableListOf<TimelineMessageQueryFfi>()

        /** Answers with the next scripted page. */
        override suspend fun page(query: TimelineMessageQueryFfi): TimelinePageFfi {
            queries += query
            return scripted.removeFirst().invoke()
        }
    }

    private companion object {
        const val NOW_MILLIS = 1_000_000L
        val SENDER = "aa".repeat(32)
        val OTHER_SENDER = "bb".repeat(32)

        /** MDK's account-search order, newest first. */
        val ACCOUNT_ORDER: Comparator<TimelineMessageRecordFfi> =
            compareByDescending<TimelineMessageRecordFfi> { it.globalSearchCursor() }

        val EMPTY_TOKENS = MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0))

        /** A timeline record with the fields search reads; [invalidation] marks a losing-branch row. */
        @Suppress("LongParameterList")
        fun record(
            group: String,
            id: String,
            at: ULong,
            text: String = "hello there",
            sender: String = SENDER,
            kind: ULong = 9uL,
            deleted: Boolean = false,
            expiresAt: ULong? = null,
            invalidation: String? = null,
        ) = TimelineMessageRecordFfi(
            clientToken = null,
            hasReports = false,
            poll = null,
            messageIdHex = id,
            sourceMessageIdHex = null,
            sourceEpoch = null,
            retentionSeconds = expiresAt?.let { 1uL },
            retentionExpiresAt = expiresAt,
            direction = "received",
            groupIdHex = group,
            sender = sender,
            plaintext = text,
            contentTokens = EMPTY_TOKENS,
            kind = kind,
            tags = emptyList(),
            sticker = null,
            timelineAt = at,
            receivedAt = at,
            replyToMessageIdHex = null,
            replyPreview = null,
            mediaJson = null,
            media = emptyList(),
            agentTextStreamJson = null,
            groupSystem = null,
            reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
            edit = null,
            deleted = deleted,
            deletionSource = DeletionSourceFfi.UNKNOWN,
            deletedByMessageIdHex = null,
            invalidationStatus = invalidation,
            recipientConfirmed = false,
            importedFrom = null,
            historyImportProvenance = null,
        )
    }
}
