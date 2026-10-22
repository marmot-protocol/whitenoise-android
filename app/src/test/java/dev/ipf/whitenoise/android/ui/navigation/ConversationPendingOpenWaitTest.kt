package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.state.INITIAL_PRESENTATION_EVENT_CARD_WARM_BUDGET_MILLIS
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat-list open waits its usual bound, and the card budget more only while a first page is held for cards. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationPendingOpenWaitTest {
    /** A conversation ready within the usual bound opens as soon as it is ready. */
    @Test
    fun readyConversationOpensAtOnce() =
        runTest {
            val ready = awaitPreparedConversationOpen(awaitReady = { delay(40) }, holdingForEventCards = { true })

            assertTrue(ready)
            assertEquals(40L, currentTime)
        }

    /** A conversation without held cards never waits longer than before. */
    @Test
    fun conversationWithoutHeldCardsKeepsTheUsualBound() =
        runTest {
            val ready =
                awaitPreparedConversationOpen(
                    awaitReady = { delay(COLD_CARD_READ_MILLIS) },
                    holdingForEventCards = { false },
                )

            assertFalse(ready)
            assertEquals(CONVERSATION_PENDING_OPEN_TIMEOUT_MILLIS, currentTime)
        }

    /** A first page held for a 400 ms cold card read opens when the cards are ready, not at the usual bound. */
    @Test
    fun heldCardsExtendTheWaitUntilTheyAreReady() =
        runTest {
            // Readiness is a state the wait observes, so each wait sees the same moment it turns true.
            val ready =
                awaitPreparedConversationOpen(
                    awaitReady = { delay(COLD_CARD_READ_MILLIS - currentTime) },
                    holdingForEventCards = { true },
                )

            assertTrue(ready)
            assertEquals(COLD_CARD_READ_MILLIS, currentTime)
        }

    /** A conversation that never becomes ready still opens once the card budget is spent too. */
    @Test
    fun heldCardsNeverHoldTheTapBeyondBothBounds() =
        runTest {
            val ready =
                awaitPreparedConversationOpen(
                    awaitReady = { awaitCancellation() },
                    holdingForEventCards = { true },
                )

            assertFalse(ready)
            assertEquals(
                CONVERSATION_PENDING_OPEN_TIMEOUT_MILLIS + INITIAL_PRESENTATION_EVENT_CARD_WARM_BUDGET_MILLIS,
                currentTime,
            )
        }

    private companion object {
        /** Slower than the usual open bound and within the card budget, as a cold cache read can be. */
        const val COLD_CARD_READ_MILLIS = 400L
    }
}
