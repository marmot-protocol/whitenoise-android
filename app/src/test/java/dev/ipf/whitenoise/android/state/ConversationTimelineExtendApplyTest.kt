package dev.ipf.whitenoise.android.state

import android.os.Looper
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.core.TimelineProjector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

/**
 * An older page slides the bounded window by one page. Rows the window still holds must keep their
 * projected items and their place, and must not be re-parsed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationTimelineExtendApplyTest {
    /** A newer replacement invalidates an older EXTEND waiting on the worker lane. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun newerWindowDiscardsAnOlderSuspendedExtendPreparation() =
        runTest {
            val dispatcher = PausedPreparationDispatcher()
            val scripted =
                ScriptedConversationLiveSubscriptions(
                    timelineScripts = emptyList(),
                    group = conversationTimelineTestGroup(),
                )
            val appState = conversationTimelineTestAppState(scripted.subscriptions)
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = conversationTimelineTestGroup(),
                    initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                    groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
                    windowPreparationDispatcher = dispatcher,
                )
            try {
                val stale =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        controller.applyTimelinePage(
                            page(listOf(row(FIRST), row(SECOND))),
                            replaceWindow = false,
                            updatePagination = true,
                        )
                    }
                assertFalse(stale.isCompleted)
                val newest =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        controller.applyTimelinePage(
                            page(listOf(row(SECOND), row(THIRD))),
                            replaceWindow = true,
                            updatePagination = true,
                        )
                    }

                dispatcher.runPending()
                advanceUntilIdle()

                assertEquals(emptyList<String>(), stale.await())
                newest.await()
                assertEquals(listOf(SECOND, THIRD), timelineMessageIds(controller))
            } finally {
                controller.onCleared()
            }
        }

    /** An index writer during preparation cannot leave a page row hidden, and loses nothing it retained. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun extendRechecksLiveIndexesAfterSuspendedPreparation() =
        runTest {
            val dispatcher = PausedPreparationDispatcher()
            val measurements = mutableListOf<WindowApplyPerformanceSample>()
            val scripted =
                ScriptedConversationLiveSubscriptions(
                    timelineScripts = emptyList(),
                    group = conversationTimelineTestGroup(),
                )
            val appState = conversationTimelineTestAppState(scripted.subscriptions)
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = conversationTimelineTestGroup(),
                    initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                    groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
                    windowPreparationDispatcher = dispatcher,
                    onWindowApplyMeasured = measurements::add,
                )
            try {
                val authoritativePage = page(listOf(row(SECOND), row(THIRD)))
                val opening =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        controller.applyTimelinePage(authoritativePage, replaceWindow = true, updatePagination = true)
                    }
                dispatcher.runPending()
                advanceUntilIdle()
                opening.await()
                assertEquals(listOf(SECOND, THIRD), timelineMessageIds(controller))
                measurements.clear()
                val heldSecond = controller.timelineRecords.getValue(SECOND)
                val extend =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        controller.applyTimelinePage(authoritativePage, replaceWindow = false, updatePagination = true)
                    }
                assertFalse(extend.isCompleted)

                controller.removeProjectedRecord(SECOND)
                controller.timelineRecords[SECOND] = heldSecond
                controller.timelineRecords[THIRD] = row(THIRD, "changed during preparation")
                controller.timelineRecords[FIRST] = row(FIRST)
                dispatcher.runPending()
                advanceUntilIdle()
                extend.await()

                assertEquals(listOf(SECOND, THIRD), timelineMessageIds(controller))
                assertTrue(FIRST in controller.timelineRecords)
                assertTrue("msg:$SECOND" in controller.timelineItemsById)
                assertEquals("body", controller.timelineRecords.getValue(THIRD).plaintext)
                assertEquals(2, measurements.single().committedProjectionCount)
            } finally {
                controller.onCleared()
            }
        }

    /** A 200-row extension prepares off-main and projects only the changed row. */
    @Test
    fun largeWindowMeasuresPreparationSeparatelyAndCommitsOnlyItsDiff() =
        runBlocking {
            val seed =
                (0 until 200).map { index ->
                    timelineRecord(
                        messageId = index.toString(16).padStart(64, '0'),
                        timelineAt = index.toULong(),
                        plaintext = "row-$index",
                    )
                }
            val measurements = mutableListOf<WindowApplyPerformanceSample>()
            withController(seed = seed, onWindowApplyMeasured = measurements::add) { controller, _ ->
                measurements.clear()
                val extended =
                    seed.mapIndexed { index, record ->
                        if (index == 99) record.copy(plaintext = "edited") else record
                    }

                controller.applyTimelinePage(
                    page(extended),
                    replaceWindow = false,
                    updatePagination = true,
                )

                val sample = measurements.single()
                assertEquals(200, sample.preparedRowCount)
                assertEquals(1, sample.committedProjectionCount)
                assertTrue(sample.preparationNanos >= 0L)
                assertTrue(sample.mainCommitNanos >= 0L)
            }
        }

    /**
     * Rows the extended window kept are not re-projected: their held record survives by identity,
     * which is what makes a page cost the rows that changed rather than the whole window.
     *
     * Their rendered item is still re-stamped, because sliding the window does move them — see
     * [extendRestampsKeptRowsSoTheSlidWindowKeepsItsOrder].
     */
    @Test
    fun extendKeepsUnchangedRecordsByIdentity() =
        runBlocking {
            withController(seed = listOf(row(SECOND), row(THIRD))) { controller, _ ->
                val before = controller.timelineRecords.toMap()

                controller.applyTimelinePage(
                    page(listOf(row(FIRST), row(SECOND), row(THIRD))),
                    replaceWindow = false,
                    updatePagination = true,
                )

                assertSame(before.getValue(SECOND), controller.timelineRecords.getValue(SECOND))
                assertSame(before.getValue(THIRD), controller.timelineRecords.getValue(THIRD))
                assertNotNull(controller.timelineRecords[FIRST])
            }
        }

    /**
     * The regression this mode could most easily introduce: a kept row skips re-projection, so its
     * ordinal must be re-stamped or the slid window would reorder history.
     */
    @Test
    fun extendRestampsKeptRowsSoTheSlidWindowKeepsItsOrder() =
        runBlocking {
            withController(seed = listOf(row(SECOND), row(THIRD))) { controller, _ ->
                controller.applyTimelinePage(
                    page(listOf(row(FIRST), row(SECOND), row(THIRD))),
                    replaceWindow = false,
                    updatePagination = true,
                )

                assertEquals(listOf(FIRST, SECOND, THIRD), timelineMessageIds(controller))
            }
        }

    /** Rows the window dropped stay in the timeline, in order, so paging back to them rebuilds nothing. */
    @Test
    fun extendRetainsRowsTheWindowNoLongerHolds() =
        runBlocking {
            withController(seed = listOf(row(SECOND), row(THIRD))) { controller, _ ->
                controller.applyTimelinePage(
                    page(listOf(row(FIRST), row(SECOND)), hasMoreAfter = true),
                    replaceWindow = false,
                    updatePagination = true,
                )

                assertEquals(listOf(FIRST, SECOND, THIRD), timelineMessageIds(controller))
                assertTrue(THIRD in controller.timelineRecords)
            }
        }

    /** A held row inside the window's span that the page no longer carries was removed, so it leaves. */
    @Test
    fun extendDropsHeldRowsTheWindowProvesGone() =
        runBlocking {
            withController(seed = listOf(row(FIRST), row(SECOND), row(THIRD))) { controller, _ ->
                controller.applyTimelinePage(
                    page(listOf(row(FIRST), row(THIRD)), hasMoreAfter = true),
                    replaceWindow = false,
                    updatePagination = true,
                )

                assertEquals(listOf(FIRST, THIRD), timelineMessageIds(controller))
                assertTrue(SECOND !in controller.timelineRecords)
            }
        }

    /** A final edge says nothing lies beyond it, so held rows past that edge were removed too. */
    @Test
    fun extendDropsHeldRowsBeyondAFinalEdge() =
        runBlocking {
            withController(seed = listOf(row(FIRST), row(SECOND), row(THIRD))) { controller, _ ->
                controller.applyTimelinePage(
                    page(listOf(row(SECOND), row(THIRD)), hasMoreBefore = false),
                    replaceWindow = false,
                    updatePagination = true,
                )

                assertEquals(listOf(SECOND, THIRD), timelineMessageIds(controller))
                assertTrue(FIRST !in controller.timelineRecords)
            }
        }

    /** A window sharing no row with the held ones is a new place in history, however it was requested. */
    @Test
    fun extendWithNoSharedRowReplacesTheWindow() =
        runBlocking {
            withController(seed = listOf(row(SECOND), row(THIRD))) { controller, _ ->
                controller.applyTimelinePage(
                    page(listOf(row(FIRST))),
                    replaceWindow = false,
                    updatePagination = true,
                )

                assertEquals(listOf(FIRST), timelineMessageIds(controller))
                assertTrue(SECOND !in controller.timelineRecords && THIRD !in controller.timelineRecords)
            }
        }

    /** Past the cap, the rows farthest from the latest window go first, from whichever end that is. */
    @Test
    fun retentionCapEvictsTheRowsFarthestFromTheLatestWindow() =
        runBlocking {
            withController(seed = rankedRows(0 until 200)) { controller, _ ->
                listOf(150 until 350, 300 until 500, 450 until 650).forEach { range ->
                    controller.applyTimelinePage(
                        page(rankedRows(range), hasMoreAfter = range.last < 649),
                        replaceWindow = false,
                        updatePagination = true,
                    )
                }

                assertEquals(MAX_RETAINED_TIMELINE_ROWS, controller.timelineRecords.size)
                assertTrue(rankedId(49) !in controller.timelineRecords)
                assertTrue(rankedId(50) in controller.timelineRecords && rankedId(649) in controller.timelineRecords)
                assertEquals((50 until 650).map(::rankedId), timelineMessageIds(controller))

                controller.applyTimelinePage(
                    page(rankedRows(0 until 200), hasMoreBefore = false, hasMoreAfter = true),
                    replaceWindow = false,
                    updatePagination = true,
                )

                assertEquals(MAX_RETAINED_TIMELINE_ROWS, controller.timelineRecords.size)
                assertTrue(rankedId(0) in controller.timelineRecords && rankedId(599) in controller.timelineRecords)
                assertTrue(rankedId(600) !in controller.timelineRecords)
                assertEquals((0 until 600).map(::rankedId), timelineMessageIds(controller))
            }
        }

    /** Markdown already parsed for a row carries across the page, so nothing re-parses unchanged text. */
    @Test
    fun extendCarriesMarkdownTokensForUnchangedText() =
        runBlocking {
            withController(seed = listOf(hydratedRow(SECOND))) { controller, _ ->
                val parsedBefore = controller.timelineRecords.getValue(SECOND).contentTokens
                assertTrue(parsedBefore.blocks.isNotEmpty())

                controller.applyTimelinePage(
                    page(listOf(row(FIRST), row(SECOND))),
                    replaceWindow = false,
                    updatePagination = true,
                )

                assertEquals(parsedBefore, controller.timelineRecords.getValue(SECOND).contentTokens)
            }
        }

    /** Edited text must still be re-parsed, so carried tokens are never applied across a change. */
    @Test
    fun extendRehydratesWhenTextChanged() =
        runBlocking {
            withController(seed = listOf(hydratedRow(SECOND))) { controller, _ ->
                controller.applyTimelinePage(
                    page(listOf(row(SECOND, plaintext = "edited"))),
                    replaceWindow = false,
                    updatePagination = true,
                )

                assertTrue(
                    controller.timelineRecords
                        .getValue(SECOND)
                        .contentTokens.blocks
                        .isEmpty(),
                )
            }
        }

    /** A replacement still rebuilds everything, so open, jump and reconnect are unaffected. */
    @Test
    fun replaceStillRebuildsTheWholeWindow() =
        runBlocking {
            withController(seed = listOf(row(SECOND), row(THIRD))) { controller, _ ->
                val before = controller.timeline.first { it.record.messageIdHex == SECOND }

                controller.applyTimelinePage(
                    page(listOf(row(SECOND), row(THIRD))),
                    replaceWindow = true,
                    updatePagination = true,
                )

                val after = controller.timeline.first { it.record.messageIdHex == SECOND }
                assertEquals(listOf(SECOND, THIRD), timelineMessageIds(controller))
                assertTrue(before !== after)
            }
        }

    /**
     * A newer page can install the authoritative row for a send whose optimistic bubble is still
     * pending, before the live update arrives. The row it newly adds must consume that bubble, or
     * the reader sees the same message twice.
     */
    @Test
    fun extendReconcilesAPendingSendANewerPageConfirms() =
        runBlocking {
            withController(seed = listOf(row(SECOND))) { controller, appState ->
                val optimistic = appState.optimisticMessages(controller.boundAccountRef, GROUP_ID)
                optimistic["msg:$TEMP_ID"] = pendingSend()
                assertEquals(1, optimistic.size)

                // The authoritative row for that same send: the reader's own message, same text.
                val confirmed = row(THIRD).copy(direction = "sent")

                controller.applyTimelinePage(
                    page(listOf(row(SECOND), confirmed)),
                    replaceWindow = false,
                    updatePagination = true,
                    reconcileNewExtendedRecords = true,
                )

                assertTrue("the confirmed send's optimistic bubble must be consumed", optimistic.isEmpty())
                assertEquals(listOf(SECOND, THIRD), timelineMessageIds(controller))
            }
        }

    /** An optimistic send still awaiting confirmation, as the send path leaves one. */
    private fun pendingSend(): TimelineMessage {
        val record =
            timelineRecord(messageId = TEMP_ID, timelineAt = SENT_AT, plaintext = "body")
                .copy(direction = "sent")
        return TimelineMessage(
            id = "msg:$TEMP_ID",
            record = TimelineProjector.toAppMessageRecord(record),
            status = MessageStatus.Pending,
            timelineOrder = 300uL,
        )
    }

    /** A window page; the edge flags matter, since a final edge tells retention what lies beyond it is gone. */
    private fun page(
        messages: List<TimelineMessageRecordFfi>,
        hasMoreBefore: Boolean = true,
        hasMoreAfter: Boolean = false,
    ) = TimelinePageFfi(messages = messages, hasMoreBefore = hasMoreBefore, hasMoreAfter = hasMoreAfter)

    /** A plain authoritative row whose position follows its id's rank. */
    private fun row(
        messageId: String,
        plaintext: String = "body",
    ) = timelineRecord(
        messageId = messageId,
        timelineAt = RANK.getValue(messageId),
        plaintext = plaintext,
    )

    /** Rows whose id and position both follow [indices], for windows wider than the named rows. */
    private fun rankedRows(indices: IntRange) =
        indices.map { index ->
            timelineRecord(
                messageId = rankedId(index),
                timelineAt = 1_000uL + index.toULong(),
                plaintext = "row $index",
            )
        }

    /** The id [rankedRows] gives the row at [index]. */
    private fun rankedId(index: Int) = index.toString(16).padStart(64, '0')

    /** The same row with Markdown already parsed, as hydration would leave it. */
    private fun hydratedRow(messageId: String) = row(messageId).withMarkdownTokens(parsedDocument())

    /** A minimal non-empty Markdown document, as the parser would leave one. */
    private fun parsedDocument() =
        MarkdownDocumentFfi(
            truncated = false,
            blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(MarkdownInlineFfi.Text("body")))),
            blankLinesBefore = ByteArray(0),
        )

    /** Drains the main looper so controller startup and Compose state settle. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class PausedPreparationDispatcher : CoroutineDispatcher() {
        private val pending = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            pending += block
        }

        /** Resumes queued worker tasks without advancing unrelated controller work. */
        fun runPending() {
            while (true) pending.poll()?.run() ?: return
        }
    }

    /** Owns a controller seeded with [seed] as its opening window. */
    private suspend fun withController(
        seed: List<TimelineMessageRecordFfi>,
        onWindowApplyMeasured: (WindowApplyPerformanceSample) -> Unit = {},
        block: suspend (ConversationController, WhiteNoiseAppState) -> Unit,
    ) {
        val subscription = ScriptedConversationTimelineSubscription(snapshotPage = page(seed))
        val scripted =
            ScriptedConversationLiveSubscriptions(
                timelineScripts = listOf(subscription),
                group = conversationTimelineTestGroup(),
            )
        val appState = conversationTimelineTestAppState(scripted.subscriptions)
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = conversationTimelineTestGroup(),
                initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
                startOnConstruction = true,
                onWindowApplyMeasured = onWindowApplyMeasured,
            )
        try {
            // Apply the opening window here rather than waiting on subscription startup: under the
            // full suite that race leaves the timeline empty and every assertion below is vacuous.
            settle()
            controller.applyTimelinePage(page(seed), replaceWindow = true, updatePagination = true)
            check(timelineMessageIds(controller).isNotEmpty()) { "the seeded window must be applied" }
            block(controller, appState)
            settle()
        } finally {
            controller.onCleared()
            awaitOpenedTimelineSubscriptionsClosed(scripted)
        }
    }

    private companion object {
        val FIRST = "11".repeat(32)
        val SECOND = "22".repeat(32)
        val THIRD = "33".repeat(32)
        const val TEMP_ID = "0d2b6c1e-4f6a-4b1e-9c1d-3a7f0b2e5c88"
        const val SENT_AT = 300uL // matches THIRD's rank, so the projection lands on the same instant
        val GROUP_ID = ConversationTimelineTestIds.GROUP_ID
        val RANK = mapOf(FIRST to 100uL, SECOND to 200uL, THIRD to 300uL)
    }
}
