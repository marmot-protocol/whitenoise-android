package dev.ipf.whitenoise.android.state

import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.LOADED
import dev.ipf.whitenoise.android.state.EventCardConversationFixture.Companion.LOADING
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
import kotlin.math.absoluteValue

/**
 * Which cards a later page's read reaches (#2238): cards a first page cut short by its budget left unread, and, after
 * a jump, the cards nearest the message jumped to first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class LaterPageWarmReachTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val navigationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Cancels any page or jump still in flight when a test ends. */
    @After
    fun cancelNavigation() {
        navigationScope.cancel()
    }

    /**
     * A first page whose card read is cut short by its budget, with its unread cards' rows off screen: the next older
     * page reads those on-screen rows' cards too, so when the reader scrolls to them they first compose Loaded.
     */
    @Test
    fun cardsAFirstPageLeftUnreadAreReadByTheNextPage() {
        val stuck = CountDownLatch(1)
        val reads = AtomicInteger()
        val cards = cardRows(1..MANY_CARDS)
        val inView = cards.takeLast(BATCH).mapTo(HashSet()) { it.messageIdHex }
        val fixture =
            EventCardConversationFixture(
                composeRule = composeRule,
                snapshotPage = timelinePage(*cards.toTypedArray()).copy(hasMoreBefore = true),
                backwardsPage = timelinePage(timelineRecord(OLDER_ID, 0uL, plaintext = "older"), *cards.toTypedArray()),
                // The first page's second batch never answers; every other read does.
                cacheRead = { if (reads.incrementAndGet() == 2) stuck.await(NEVER_SECONDS, TimeUnit.SECONDS) },
            )
        try {
            fixture.composesRow = { it in inView }
            fixture.compose()
            fixture.awaitIdleCondition { fixture.cacheReads.get() >= 2 }
            shadowOf(Looper.getMainLooper()).idleFor(
                Duration.ofMillis(INITIAL_PRESENTATION_EVENT_CARD_WARM_BUDGET_MILLIS + 1L),
            )
            fixture.awaitIdleCondition { fixture.controller.hasPreparedInitialPresentation }

            var advanced: Boolean? = null
            navigationScope.launch { advanced = fixture.controller.loadOlderTimelinePage() }
            fixture.awaitIdleCondition { advanced != null }
            fixture.composesRow = { true }
            fixture.awaitIdleCondition { fixture.composed.size >= MANY_CARDS }

            val states = fixture.composed.map(::describe)
            assertFalse("a card the first page left unread composed Loading", LOADING in states)
            assertEquals(MANY_CARDS, states.toSet().size)
        } finally {
            stuck.countDown()
            fixture.close()
        }
    }

    /**
     * A far jump into a window dense with cards, whose first batch is read and the rest never: the batch read is the
     * one nearest the message jumped to, not the window's newest.
     */
    @Test
    fun farJumpReadsTheCardsNearestItsTargetFirst() {
        val stuck = CountDownLatch(1)
        val reads = AtomicInteger()
        val cards = cardRows(1..MANY_CARDS)
        val target = cards.single { it.timelineAt == TARGET_AT }
        val window = timelinePage(*cards.toTypedArray())
        val fixture =
            EventCardConversationFixture(
                composeRule = composeRule,
                snapshotPage = timelinePage(timelineRecord(NEWEST_ID, NEWEST_AT, plaintext = "plain newest")),
                jumpOutcomes = mutableListOf(ConversationJumpOutcome.Window(TimelinePageOutcome.Advanced(window))),
                cacheRead = { if (reads.incrementAndGet() > 1) stuck.await(NEVER_SECONDS, TimeUnit.SECONDS) },
            )
        try {
            fixture.compose()
            fixture.awaitIdleCondition { fixture.controller.hasPreparedInitialPresentation }
            var landed: MessageAvailability? = null
            navigationScope.launch { landed = fixture.controller.loadMessageAvailability(target.messageIdHex) }
            fixture.awaitIdleCondition { fixture.cacheReads.get() >= 2 }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LATER_PAGE_EVENT_CARD_WARM_BUDGET_MILLIS + 1L))
            fixture.awaitIdleCondition { landed != null && fixture.composed.size >= MANY_CARDS }

            val byDistance =
                compareBy<TimelineMessageRecordFfi> { (it.timelineAt.toLong() - TARGET_AT.toLong()).absoluteValue }
                    .thenByDescending { it.timelineAt }
            val nearest =
                cards
                    .sortedWith(byDistance)
                    .take(BATCH)
                    .mapTo(HashSet()) { "$LOADED ${eventIdOf(it.timelineAt.toInt())}" }
            val loaded =
                fixture.composed
                    .map(::describe)
                    .filter { it.startsWith(LOADED) }
                    .toSet()
            assertEquals("the batch read is the one nearest the target", nearest, loaded)
        } finally {
            stuck.countDown()
            fixture.close()
        }
    }

    /** Rows at the times in [times], the row at time `i` naming the card of event [eventIdOf] `i`. */
    private fun cardRows(times: IntRange): List<TimelineMessageRecordFfi> =
        times.map { index ->
            val note = noteBech32(eventIdOf(index))
            timelineRecord(index.toString(16).padStart(64, 'c'), index.toULong(), "nostr:$note")
        }

    /** The event id of the [index]th card. */
    private fun eventIdOf(index: Int): String = index.toString(16).padStart(64, '0')

    private companion object {
        val OLDER_ID = "0f".repeat(32)
        val NEWEST_ID = "d1".repeat(32)
        const val MANY_CARDS = 40
        const val BATCH = 16
        const val TARGET_AT = 12uL
        const val NEWEST_AT = 100uL
        const val NEVER_SECONDS = 120L
    }
}
