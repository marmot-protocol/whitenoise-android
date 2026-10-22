package dev.ipf.whitenoise.android.state

import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.COLD_CACHE_READ_MILLIS
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.EVENT_ID
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.LOADED
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.LOADING
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.NOTE
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.READ_BLOCK_SECONDS
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.describe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A previously resolved event card on a later page, paged, anchored or delivered after the conversation's first page,
 * through the real controller, its card read before the page is applied, and the bubble's card resolver (#2238). The
 * first page names no card, so only the later page's read can keep its card row from composing Loading.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class LaterPageEventCardFirstFrameTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val pagingScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Cancels any page still in flight when a test ends. */
    @After
    fun cancelPaging() {
        pagingScope.cancel()
    }

    /**
     * A cold 400 ms cache read composes the older card Loaded on its first frame: the page is applied only once its
     * cards are read, and reports advanced with its rows on screen.
     */
    @Test
    fun olderPageCardComposesLoadedAfterASlowCacheRead() {
        val fixture = pagedConversation(cacheRead = { Thread.sleep(COLD_CACHE_READ_MILLIS) })
        try {
            val page = fixture.openThenPageOlder()
            fixture.awaitIdleCondition { fixture.cacheReads.get() >= 1 }
            assertEquals("the page waits for its read", listOf(NEWEST_ID), timelineMessageIds(fixture.controller))
            assertNull("the page is not applied during its read", page.advanced)

            fixture.awaitIdleCondition { page.advanced != null && fixture.composed.isNotEmpty() }

            assertEquals(true, page.advanced)
            assertTrue("the page finished with its card row on screen", page.cardRowShownOnCompletion)
            assertEquals(
                "no Loading card may compose before the cached card",
                listOf("$LOADED $EVENT_ID"),
                fixture.composed.map(::describe).distinct(),
            )
            assertEquals(listOf(CARD_OLDER_ID, PLAIN_OLDER_ID, NEWEST_ID), timelineMessageIds(fixture.controller))
        } finally {
            fixture.close()
        }
    }

    /** A cache read that never answers cannot keep the older page back past the card budget. */
    @Test
    fun olderPageCardRowPublishesAtTheBudgetWhenTheReadNeverAnswers() {
        val blocked = CountDownLatch(1)
        val fixture = pagedConversation(cacheRead = { blocked.await(NEVER_ANSWERS_SECONDS, TimeUnit.SECONDS) })
        try {
            val page = fixture.openThenPageOlder()
            fixture.awaitIdleCondition { fixture.cacheReads.get() >= 1 }
            assertEquals(listOf(NEWEST_ID), timelineMessageIds(fixture.controller))
            assertTrue("nothing composes a card during the read", fixture.composed.isEmpty())

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LATER_PAGE_EVENT_CARD_WARM_BUDGET_MILLIS + 1L))
            fixture.awaitIdleCondition { page.advanced != null && fixture.composed.isNotEmpty() }

            assertEquals(true, page.advanced)
            assertEquals(listOf(CARD_OLDER_ID, PLAIN_OLDER_ID, NEWEST_ID), timelineMessageIds(fixture.controller))
            assertEquals("past the budget the card resolves itself", LOADING, describe(fixture.composed.first()))
        } finally {
            blocked.countDown()
            fixture.close()
        }
    }

    /**
     * An older page whose card row sits between two plain rows is applied in one publish after its read, so the reader
     * never sees the plain rows first and the card row pushed in between them later.
     */
    @Test
    fun olderPageJoinsTheVisibleRowsInOnePublish() {
        val read = CountDownLatch(1)
        val newest = timelineRecord(NEWEST_ID, 4uL, plaintext = "plain newest")
        val fixture =
            EventCardConversationFixture(
                composeRule = composeRule,
                snapshotPage = timelinePage(newest).copy(hasMoreBefore = true),
                backwardsPage =
                    timelinePage(
                        timelineRecord(PLAIN_OLDEST_ID, 1uL, plaintext = "plain oldest"),
                        timelineRecord(CARD_OLDER_ID, 2uL, plaintext = "nostr:$NOTE"),
                        timelineRecord(PLAIN_OLDER_ID, 3uL, plaintext = "plain older"),
                        newest,
                    ),
                cacheRead = { read.await(READ_BLOCK_SECONDS, TimeUnit.SECONDS) },
            )
        try {
            val page = fixture.openThenPageOlder()
            fixture.awaitIdleCondition { fixture.cacheReads.get() >= 1 }
            assertEquals("no row of the page shows before its read", listOf(NEWEST_ID), fixture.composedOrders.last())

            read.countDown()
            fixture.awaitIdleCondition { page.advanced != null }

            val released = listOf(PLAIN_OLDEST_ID, CARD_OLDER_ID, PLAIN_OLDER_ID, NEWEST_ID)
            assertEquals(released, timelineMessageIds(fixture.controller))
            assertNoRowInsertedAmongVisibleRows(fixture.composedOrders)
            assertEquals(
                "the page's rows appear together, in one frame",
                listOf(listOf(NEWEST_ID), released),
                fixture.composedOrders.filter { it.isNotEmpty() },
            )
        } finally {
            read.countDown()
            fixture.close()
        }
    }

    /**
     * A live window carrying a card row, then a plain window behind it: the plain window queues behind the card read,
     * as live windows always queue behind the one being applied, so its row appears after the card row, below it, and
     * never among rows already shown. The card first composes Loaded.
     */
    @Test
    fun liveWindowBehindAWarmingCardWindowShowsInOrder() {
        val read = CountDownLatch(1)
        val newest = timelineRecord(NEWEST_ID, 1uL, plaintext = "plain newest")
        val card = timelineRecord(CARD_LIVE_ID, 2uL, plaintext = "nostr:$NOTE")
        val plain = timelineRecord(PLAIN_LIVE_ID, 3uL, plaintext = "plain live")
        val fixture =
            EventCardConversationFixture(
                composeRule = composeRule,
                snapshotPage = timelinePage(newest),
                cacheRead = { read.await(READ_BLOCK_SECONDS, TimeUnit.SECONDS) },
            )
        try {
            fixture.compose()
            fixture.awaitIdleCondition { fixture.controller.hasPreparedInitialPresentation }
            fixture.timelineScript.emitWindow(timelinePage(newest, card))
            fixture.awaitIdleCondition { fixture.drainLiveWindows() && fixture.cacheReads.get() >= 1 }

            fixture.timelineScript.emitWindow(timelinePage(newest, card, plain))
            assertEquals(
                "both windows wait for the card read",
                listOf(NEWEST_ID),
                timelineMessageIds(fixture.controller),
            )

            read.countDown()
            fixture.awaitIdleCondition {
                fixture.drainLiveWindows() && PLAIN_LIVE_ID in timelineMessageIds(fixture.controller)
            }

            val shown = listOf(NEWEST_ID, CARD_LIVE_ID, PLAIN_LIVE_ID)
            assertEquals(shown, timelineMessageIds(fixture.controller))
            assertNoRowInsertedAmongVisibleRows(fixture.composedOrders)
            assertTrue(
                "the plain row never showed before the card row",
                fixture.composedOrders.none { PLAIN_LIVE_ID in it && CARD_LIVE_ID !in it },
            )
            assertEquals(listOf("$LOADED $EVENT_ID"), fixture.composed.map(::describe).distinct())
        } finally {
            read.countDown()
            fixture.close()
        }
    }

    /** An older page that names no card publishes every row at once and never reads the card cache. */
    @Test
    fun olderPageWithoutCardsPublishesAtOnce() {
        val fixture = pagedConversation(cacheRead = {}, olderCardText = "plain, no card here")
        try {
            val page = fixture.openThenPageOlder()
            fixture.awaitIdleCondition { page.advanced != null }

            assertEquals(true, page.advanced)
            assertTrue("every row was on screen when the page finished", page.cardRowShownOnCompletion)
            assertEquals(listOf(CARD_OLDER_ID, PLAIN_OLDER_ID, NEWEST_ID), timelineMessageIds(fixture.controller))
            assertEquals(0, fixture.cacheReads.get())
        } finally {
            fixture.close()
        }
    }

    /** The result of one older page, filled in on the main thread when it finishes. */
    private class OlderPage {
        @Volatile var advanced: Boolean? = null

        @Volatile var cardRowShownOnCompletion = false
    }

    /** Composes the conversation, waits for its first page, then pages older on the main thread. */
    private fun EventCardConversationFixture.openThenPageOlder(): OlderPage {
        compose()
        awaitIdleCondition { controller.hasPreparedInitialPresentation && controller.timeline.isNotEmpty() }
        val page = OlderPage()
        pagingScope.launch {
            val advanced = controller.loadOlderTimelinePage()
            page.cardRowShownOnCompletion = CARD_OLDER_ID in timelineMessageIds(controller)
            page.advanced = advanced
        }
        return page
    }

    /**
     * A conversation whose first page holds one plain message, with an older page holding [olderCardText], by default
     * naming a previously resolved card, beside a plain older message.
     */
    private fun pagedConversation(
        cacheRead: () -> Unit,
        olderCardText: String = "nostr:$NOTE",
    ): EventCardConversationFixture {
        val newest = timelineRecord(NEWEST_ID, 3uL, plaintext = "plain newest")
        return EventCardConversationFixture(
            composeRule = composeRule,
            snapshotPage = timelinePage(newest).copy(hasMoreBefore = true),
            backwardsPage =
                timelinePage(
                    timelineRecord(CARD_OLDER_ID, 1uL, plaintext = olderCardText),
                    timelineRecord(PLAIN_OLDER_ID, 2uL, plaintext = "plain older"),
                    newest,
                ),
            cacheRead = cacheRead,
        )
    }

    /**
     * The device path behind #2238's later-page jump: in a chat holding only the reader's own messages, the visible-row
     * report answers with a window that reaches older own rows, and its card row composed Loading. Its cards are now
     * read before that window is applied, so the card row composes Loaded.
     */
    @Test
    fun ownCardRowFromAVisibleRowReportComposesLoaded() {
        val read = CountDownLatch(1)
        val newest = timelineRecord(NEWEST_ID, 3uL, plaintext = "plain newest").copy(direction = "sent")
        val fixture =
            EventCardConversationFixture(
                composeRule = composeRule,
                snapshotPage = timelinePage(newest).copy(hasMoreBefore = true),
                anchorPage =
                    timelinePage(
                        timelineRecord(PLAIN_OLDER_ID, 1uL, plaintext = "plain older").copy(direction = "sent"),
                        timelineRecord(CARD_OLDER_ID, 2uL, plaintext = "nostr:$NOTE").copy(direction = "sent"),
                        newest,
                    ),
                cacheRead = { read.await(READ_BLOCK_SECONDS, TimeUnit.SECONDS) },
            )
        try {
            fixture.compose()
            fixture.awaitIdleCondition { fixture.controller.hasPreparedInitialPresentation }
            pagingScope.launch { fixture.controller.reportVisibleMessage(NEWEST_ID) }
            fixture.awaitIdleCondition { fixture.cacheReads.get() >= 1 }
            assertEquals(
                "the reported window waits for its card read",
                listOf(NEWEST_ID),
                timelineMessageIds(fixture.controller),
            )

            read.countDown()
            fixture.awaitIdleCondition { CARD_OLDER_ID in timelineMessageIds(fixture.controller) }

            assertEquals(listOf("$LOADED $EVENT_ID"), fixture.composed.map(::describe).distinct())
            assertEquals(
                listOf(listOf(NEWEST_ID), listOf(PLAIN_OLDER_ID, CARD_OLDER_ID, NEWEST_ID)),
                fixture.composedOrders.filter { it.isNotEmpty() },
            )
        } finally {
            read.countDown()
            fixture.close()
        }
    }

    /**
     * Moves the main looper's clock just past the live pipeline's batch drain, so a delivered window is applied, and
     * returns true so it can lead a wait's condition. The step is far below the card budget.
     */
    private fun EventCardConversationFixture.drainLiveWindows(): Boolean {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LIVE_DRAIN_STEP_MILLIS))
        return controller.timeline.isNotEmpty()
    }

    /**
     * Fails when a frame shows a new row between two rows the previous frame already showed next to each other, or
     * moves rows the reader saw out of their order: every frame keeps the rows it shares with the one before as one
     * contiguous run, in the same order.
     */
    private fun assertNoRowInsertedAmongVisibleRows(orders: List<List<String>>) {
        orders.zipWithNext().forEach { (before, after) ->
            val kept = after.filter { it in before }
            val firstKept = after.indexOfFirst { it in before }
            val keptRun = if (firstKept < 0) emptyList() else after.subList(firstKept, firstKept + kept.size)
            assertEquals("rows moved or were split going from $before to $after", kept, keptRun)
            assertEquals("visible rows reordered going from $before to $after", before.filter { it in after }, kept)
        }
    }

    private companion object {
        val CARD_OLDER_ID = "c1".repeat(32)
        val PLAIN_OLDER_ID = "c2".repeat(32)
        val NEWEST_ID = "d1".repeat(32)
        val PLAIN_OLDEST_ID = "c3".repeat(32)
        val CARD_LIVE_ID = "e1".repeat(32)
        val PLAIN_LIVE_ID = "e2".repeat(32)
        const val LIVE_DRAIN_STEP_MILLIS = 8L

        /** Far longer than any wait in these tests, so a read that "never answers" cannot unblock one by chance. */
        const val NEVER_ANSWERS_SECONDS = 120L
    }
}
