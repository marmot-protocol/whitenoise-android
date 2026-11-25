package dev.ipf.whitenoise.android.ui.conversation.agent

import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.state.ConversationTimelineSubscriptionHandle
import dev.ipf.whitenoise.android.state.TimelinePageOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityHistoryWindowTest {
    @Test
    fun independentWindowPagesReceivesLiveUpdatesAndCloses() =
        runTest {
            val history = ActivityHistoryWindow()
            val subscription = TestActivitySubscription()
            val collect = launch { history.collect { subscription } }
            runCurrent()

            assertEquals(subscription.latest, history.page)
            assertTrue(history.move(older = true))
            assertEquals(subscription.older, history.page)
            assertTrue(history.move(older = false))
            assertEquals(subscription.latest, history.page)

            val replaced = subscription.latest.copy(hasMoreBefore = false)
            subscription.updates.send(replaced)
            runCurrent()
            assertEquals(replaced, history.page)

            collect.cancelAndJoin()
            assertEquals(listOf("cancel", "close"), subscription.teardown)
            assertNull(history.page)
        }

    @Test
    fun cancellationDuringOpenClosesTheHandleWithoutReadingIt() =
        runTest {
            val history = ActivityHistoryWindow()
            val subscription = TestActivitySubscription()
            val pendingOpen = CompletableDeferred<ConversationTimelineSubscriptionHandle>()
            val collect = launch { history.collect { pendingOpen.await() } }
            runCurrent()

            collect.cancel()
            pendingOpen.complete(subscription)
            collect.join()

            assertEquals(0, subscription.snapshotReads)
            assertEquals(listOf("cancel", "close"), subscription.teardown)
            assertNull(history.page)
        }

    private class TestActivitySubscription : ConversationTimelineSubscriptionHandle {
        val latest = TimelinePageFfi(emptyList(), true, false)
        val older = TimelinePageFfi(emptyList(), false, true)
        val updates = Channel<TimelinePageFfi>(Channel.UNLIMITED)
        val teardown = mutableListOf<String>()
        var snapshotReads = 0
            private set

        override fun snapshot(): TimelinePageFfi {
            snapshotReads += 1
            return latest
        }

        override suspend fun nextWindow(): TimelinePageFfi? = updates.receiveCatching().getOrNull()

        override suspend fun paginateBackwards(count: UInt): TimelinePageOutcome = TimelinePageOutcome.Advanced(older)

        override suspend fun paginateForwards(count: UInt): TimelinePageOutcome = TimelinePageOutcome.Advanced(latest)

        override suspend fun cancel() {
            teardown += "cancel"
        }

        override fun close() {
            teardown += "close"
        }
    }
}
