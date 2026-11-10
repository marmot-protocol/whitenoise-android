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

    /** Several senders or chats search account-wide and keep only the selected ones on the host. */
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
                    groups = setOf("chosen", "chosen2"),
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

    /** Hidden, deleted, expired and system rows never become results, and no snippet shows their text. */
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

    /** A controller whose batches run as children of the test, with the given local hides and owner check. */
    private fun TestScope.controller(
        reader: FakeReader,
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
        val EMPTY_TOKENS = MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0))

        /** A timeline record with the fields search reads. */
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
            invalidationStatus = null,
            recipientConfirmed = false,
            importedFrom = null,
            historyImportProvenance = null,
        )
    }
}
