package dev.ipf.whitenoise.android.state

import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
        val cards =
            (1..MANY_CARDS).map { index ->
                val eventId = index.toString(16).padStart(64, '0')
                timelineRecord(index.toString(16).padStart(64, 'c'), index.toULong(), "nostr:${noteBech32(eventId)}")
            }
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

    /** The dropped later-page card reads recorded so far. */
    private fun droppedWarmLines(): List<String> =
        PerformanceDiagnostics.exportLines().filter { " phase=event_card_page_warm " in it && " result=dropped " in it }

    private companion object {
        val NEWEST_ID = "d1".repeat(32)
        val EDITED_ID = "e1".repeat(32)
        const val MANY_CARDS = 40
        val DURATION = Regex(" duration_ms=(\\d+) ")
    }
}
