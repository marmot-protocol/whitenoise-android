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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * A previously resolved event card on a conversation's first page, through the real controller, its Markdown
 * hydration, first-page card warm and publish hold, and the bubble's document selection and card resolver (#2238).
 * Rows compose wherever the controller publishes them, the way every open path shows them, so what is recorded here
 * is what any route draws on its first frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class InitialEventCardFirstFrameTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A cold 400 ms cache read, slower than the usual open bound, still composes the card Loaded at first. */
    @Test
    fun slowColdCacheReadComposesTheCardLoadedOnItsFirstFrame() {
        val fixture = cardConversation(cacheRead = { Thread.sleep(COLD_CACHE_READ_MILLIS) })
        try {
            fixture.compose()
            fixture.awaitIdleCondition(fixture::openedWithACard)

            assertTrue("the read settled the hold, not the budget", fixture.cacheReads.get() >= 1)
            assertTrue("the held page published with readiness", fixture.controller.hasPublishedAuthoritativeTimeline)
            assertEquals(
                "no Loading card may compose before the cached card",
                listOf("$LOADED $EVENT_ID"),
                fixture.composed.map(::describe).distinct(),
            )
        } finally {
            fixture.close()
        }
    }

    /** A cache read that never answers cannot hold the conversation past the card budget. */
    @Test
    fun cacheReadBeyondTheBudgetStillPublishesTheConversation() {
        val blocked = CountDownLatch(1)
        val fixture = cardConversation(cacheRead = { blocked.await(READ_BLOCK_SECONDS, TimeUnit.SECONDS) })
        try {
            fixture.compose()
            fixture.awaitIdleCondition { fixture.controller.isHoldingInitialPageForEventCards }
            assertFalse("a held page is not yet published", fixture.controller.hasPublishedAuthoritativeTimeline)
            assertTrue("nothing composes while the page is held", fixture.composed.isEmpty())

            shadowOf(Looper.getMainLooper()).idleFor(
                Duration.ofMillis(INITIAL_PRESENTATION_EVENT_CARD_WARM_BUDGET_MILLIS + 1L),
            )
            fixture.awaitIdleCondition(fixture::openedWithACard)

            assertTrue(fixture.controller.hasPublishedAuthoritativeTimeline)
            assertFalse(fixture.controller.isHoldingInitialPageForEventCards)
            assertEquals("past the budget the card resolves itself", LOADING, describe(fixture.composed.first()))
        } finally {
            blocked.countDown()
            fixture.close()
        }
    }

    /** A first page that names no card is published at once, as before, without waiting for any card budget. */
    @Test
    fun pageWithoutCardsIsPublishedWithoutTheHold() {
        val fixture = cardConversation(cacheRead = {}, plaintext = "plain hello")
        try {
            fixture.awaitIdleCondition { fixture.controller.hasPublishedAuthoritativeTimeline }

            assertFalse(fixture.controller.isHoldingInitialPageForEventCards)
            assertEquals(listOf(MESSAGE_ID), timelineMessageIds(fixture.controller))
            assertEquals(0, fixture.cacheReads.get())
        } finally {
            fixture.close()
        }
    }

    /** A conversation whose first page holds one message, by default naming a previously resolved card. */
    private fun cardConversation(
        cacheRead: () -> Unit,
        plaintext: String = "nostr:$NOTE",
    ) = EventCardConversationFixture(
        composeRule = composeRule,
        snapshotPage = timelinePage(timelineRecord(MESSAGE_ID, 1uL, plaintext = plaintext)),
        cacheRead = cacheRead,
    )

    private companion object {
        val MESSAGE_ID = "d1".repeat(32)
    }
}
