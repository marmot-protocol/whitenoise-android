package dev.ipf.whitenoise.android.ui.conversation.agent

import dev.ipf.marmotkit.AgentItemStateFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.core.agent.AgentItemReader
import dev.ipf.whitenoise.android.core.agent.AgentItemState
import dev.ipf.whitenoise.android.state.ConversationTimelineSubscriptionHandle
import dev.ipf.whitenoise.android.state.TimelinePageOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun openHistoryReplacesTheRunningTurnWithItsStoppedRevision() =
        runTest {
            val turn =
                AgentRecordingFixtures
                    .load("codex_approval_allowed", events = 6)
                    .mapNotNull { it.projected }
                    .single { it.agentItem?.itemType == "turn" }
            val history = ActivityHistoryWindow()
            val subscription = TestActivitySubscription(TimelinePageFfi(listOf(turn), false, false))
            val collect = launch { history.collect { subscription } }
            runCurrent()
            assertTrue(history.onlyItem().state.isOpen)

            val running = requireNotNull(turn.agentItem)
            val stopped =
                turn.copy(agentItem = running.copy(state = AgentItemStateFfi.CANCELLED, version = running.version + 1uL))
            subscription.updates.send(TimelinePageFfi(listOf(stopped), false, false))
            runCurrent()

            val replacement = history.onlyItem()
            assertEquals(turn.messageIdHex, replacement.rowId)
            assertEquals(AgentItemState.Cancelled, replacement.state)
            collect.cancelAndJoin()
        }

    private fun ActivityHistoryWindow.onlyItem() =
        activityHistoryRows(requireNotNull(page), emptySet()).single().let { row ->
            requireNotNull(AgentItemReader.read(row.record, row.projected))
        }

    private class TestActivitySubscription(
        val latest: TimelinePageFfi = TimelinePageFfi(emptyList(), true, false),
    ) : ConversationTimelineSubscriptionHandle {
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
