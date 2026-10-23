package dev.ipf.whitenoise.android.state

import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.LOADED
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.LOADING
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.NOTE
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.READ_BLOCK_SECONDS
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.SECOND_EVENT_ID
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.SECOND_NOTE
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.describe
import dev.ipf.whitenoise.android.ui.conversation.nostr.noteBech32
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coverage of a later page's event-card read at its edges (#2238): a page naming more cards than the first page's cap,
 * a message edited while it is read, and a read that only returns after its budget ran out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class LaterPageWarmCoverageTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val pagingScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Cancels any page still in flight, and any diagnostics session, when a test ends. */
    @After
    fun cancelPaging() {
        pagingScope.cancel()
        PerformanceDiagnostics.stop()
    }

    /** An older page naming more cards than the first page's cap of 32 has every card read: all compose Loaded. */
    @Test
    fun olderPageWithManyCardsHasEveryCardRead() {
        val cards = manyCards()
        val newest = timelineRecord(NEWEST_ID, (MANY_CARDS + 1).toULong(), plaintext = "plain newest")
        val fixture =
            EventCardConversationFixture(
                composeRule = composeRule,
                snapshotPage = timelinePage(newest).copy(hasMoreBefore = true),
                backwardsPage = timelinePage(*(cards + newest).toTypedArray()),
                cacheRead = {},
            )
        try {
            val advanced = fixture.pageOlder()
            fixture.awaitIdleCondition { advanced.value != null && fixture.composed.size >= MANY_CARDS }

            val states = fixture.composed.map(::describe)
            assertFalse("no card composes Loading", LOADING in states)
            assertEquals(MANY_CARDS, states.filter { it.startsWith(LOADED) }.toSet().size)
        } finally {
            fixture.close()
        }
    }

    /**
     * A dense older page whose first batch of cards is read and whose next batch never answers: the page is applied at
     * the budget, not later, with every row's Markdown from the one parse, the cards nearest the reader (the page's
     * newest) read first and composed Loaded, and only the unread ones left to resolve themselves.
     */
    @Test
    fun readCutShortKeepsItsParseAndItsNearestCards() {
        val later = CountDownLatch(1)
        val reads = AtomicInteger()
        val cards = manyCards()
        val newest = timelineRecord(NEWEST_ID, (MANY_CARDS + 1).toULong(), plaintext = "plain newest")
        val fixture =
            EventCardConversationFixture(
                composeRule = composeRule,
                snapshotPage = timelinePage(newest).copy(hasMoreBefore = true),
                backwardsPage = timelinePage(*(cards + newest).toTypedArray()),
                cacheRead = { if (reads.incrementAndGet() > 1) later.await(NEVER_SECONDS, TimeUnit.SECONDS) },
            )
        try {
            val advanced = fixture.pageOlder()
            fixture.awaitIdleCondition { fixture.cacheReads.get() >= 2 }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LATER_PAGE_EVENT_CARD_WARM_BUDGET_MILLIS + 1L))
            fixture.awaitIdleCondition { advanced.value != null && fixture.composed.size >= MANY_CARDS }

            assertEquals("each card message is parsed once", MANY_CARDS, fixture.cardParses.get())
            val loaded =
                fixture.composed
                    .map(::describe)
                    .filter { it.startsWith(LOADED) }
                    .toSet()
            val nearest = (MANY_CARDS downTo MANY_CARDS - BATCH + 1).map { "$LOADED ${eventIdOf(it)}" }.toSet()
            assertEquals("the first batch is the page's newest cards", nearest, loaded)
        } finally {
            later.countDown()
            fixture.close()
        }
    }

    /**
     * A message edited from one card to another while its first card is read: the newer window names the second card
     * under the same message id, so that card is read too, and it first composes Loaded.
     */
    @Test
    fun messageEditedDuringTheReadHasItsNewCardRead() {
        val read = CountDownLatch(1)
        val newest = timelineRecord(NEWEST_ID, 2uL, plaintext = "plain newest")
        val edited = timelineRecord(EDITED_ID, 1uL, plaintext = "nostr:$NOTE")
        val fixture =
            EventCardConversationFixture(
                composeRule = composeRule,
                snapshotPage = timelinePage(newest).copy(hasMoreBefore = true),
                backwardsPage = timelinePage(edited, newest),
                cacheRead = { read.await(READ_BLOCK_SECONDS, TimeUnit.SECONDS) },
            )
        try {
            val advanced = fixture.pageOlder()
            fixture.awaitIdleCondition { fixture.cacheReads.get() >= 1 }
            fixture.timelineScript.installedWindow =
                InstalledConversationWindow(
                    page = timelinePage(edited.copy(plaintext = "nostr:$SECOND_NOTE"), newest),
                    frame = MarmotWindowTestFakes.conversationFrame("Timeline group"),
                )

            read.countDown()
            fixture.awaitIdleCondition { advanced.value != null && fixture.composed.isNotEmpty() }

            assertEquals(listOf("$LOADED $SECOND_EVENT_ID"), fixture.composed.map(::describe).distinct())
        } finally {
            read.countDown()
            fixture.close()
        }
    }

    /**
     * A read whose binding call only returns after the budget ran out is dropped, and its diagnostic line still reports
     * how long that binding call took, not zero.
     */
    @Test
    fun droppedReadStillReportsItsBindingTime() {
        PerformanceDiagnostics.stop()
        assumeTrue(PerformanceDiagnostics.start().active)
        val read = CountDownLatch(1)
        val newest = timelineRecord(NEWEST_ID, 2uL, plaintext = "plain newest")
        val fixture =
            EventCardConversationFixture(
                composeRule = composeRule,
                snapshotPage = timelinePage(newest).copy(hasMoreBefore = true),
                backwardsPage = timelinePage(timelineRecord(EDITED_ID, 1uL, plaintext = "nostr:$NOTE"), newest),
                cacheRead = { read.await(READ_BLOCK_SECONDS, TimeUnit.SECONDS) },
            )
        try {
            val advanced = fixture.pageOlder()
            fixture.awaitIdleCondition { fixture.cacheReads.get() >= 1 }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LATER_PAGE_EVENT_CARD_WARM_BUDGET_MILLIS + 1L))
            fixture.awaitIdleCondition { advanced.value != null }

            read.countDown()
            fixture.awaitIdleCondition { droppedWarmLines().isNotEmpty() }

            val duration =
                DURATION
                    .find(droppedWarmLines().single())
                    ?.groupValues
                    ?.get(1)
                    ?.toLong()
            assertTrue("the dropped read reported duration_ms=$duration", (duration ?: 0L) > 0L)
        } finally {
            read.countDown()
            fixture.close()
        }
    }

    /** The result of one older page, filled in on the main thread when it finishes. */
    private class PageResult {
        @Volatile var value: Boolean? = null
    }

    /** Composes the conversation, waits for its first page, then pages older on the main thread. */
    private fun EventCardConversationFixture.pageOlder(): PageResult {
        compose()
        awaitIdleCondition { controller.hasPreparedInitialPresentation && controller.timeline.isNotEmpty() }
        val result = PageResult()
        pagingScope.launch { result.value = controller.loadOlderTimelinePage() }
        return result
    }

    /** [MANY_CARDS] older rows, the row at time `i` naming the card of event [eventIdOf] `i`. */
    private fun manyCards(): List<TimelineMessageRecordFfi> =
        (1..MANY_CARDS).map { index ->
            val note = noteBech32(eventIdOf(index))
            timelineRecord(index.toString(16).padStart(64, 'c'), index.toULong(), "nostr:$note")
        }

    /** The event id of the [index]th card. */
    private fun eventIdOf(index: Int): String = index.toString(16).padStart(64, '0')

    /** The dropped later-page card reads recorded so far. */
    private fun droppedWarmLines(): List<String> =
        PerformanceDiagnostics.exportLines().filter { " phase=event_card_page_warm " in it && " result=dropped " in it }

    private companion object {
        val NEWEST_ID = "d1".repeat(32)
        val EDITED_ID = "e1".repeat(32)
        const val MANY_CARDS = 40
        const val BATCH = 16
        const val NEVER_SECONDS = 120L
        val DURATION = Regex(" duration_ms=(\\d+) ")
    }
}
