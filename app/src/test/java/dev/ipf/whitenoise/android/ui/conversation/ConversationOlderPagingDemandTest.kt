package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import dev.ipf.marmotkit.ConversationWindowRevisionFfi
import dev.ipf.whitenoise.android.state.AutomaticPagingGuard
import dev.ipf.whitenoise.android.state.CONVERSATION_AUTOMATIC_OLDER_PAGE_ATTEMPTS
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises the screen's real snapshot flow while its serialized page collector is suspended. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationOlderPagingDemandTest {
    /** Recovery before the failure reply must issue another page without a drag or geometry change. */
    @Test
    fun recoveryDuringSuspendedPageIsNotConflatedAway() =
        runTest {
            val guard = AutomaticPagingGuard(CONVERSATION_AUTOMATIC_OLDER_PAGE_ATTEMPTS)
            val finishFirstPage = CompletableDeferred<Unit>()
            val requests = mutableListOf<String?>()
            val collector =
                backgroundScope.launch {
                    olderPagingRequests {
                        ConversationOlderPagingDemand("visible", "edge", !guard.blocked, guard.recoveryGeneration)
                    }.collect { demand ->
                        requests += demand.anchorMessageId
                        if (requests.size == 1) {
                            finishFirstPage.await()
                            // Native recovery already committed when the delayed NOT_READY reply arrives.
                            guard.recordFailure(ConversationWindowRevisionFfi("active", 1uL), waitForReplacement = true)
                            guard.onWindowApplied(ConversationWindowRevisionFfi("active", 2uL))
                            Snapshot.sendApplyNotifications()
                        }
                    }
                }
            runCurrent()
            assertEquals(listOf("visible"), requests)

            finishFirstPage.complete(Unit)
            runCurrent()
            assertEquals(listOf("visible", "visible"), requests)
            repeat(3) {
                guard.onWindowApplied(ConversationWindowRevisionFfi("active", 2uL))
                Snapshot.sendApplyNotifications()
                runCurrent()
            }
            assertEquals("idle applies cannot manufacture more demand", 2, requests.size)
            collector.cancel()
        }

    /** Short successful pages can keep prefetching while the same visible row remains in the margin. */
    @Test
    fun movedLoadedEdgeReevaluatesDemandWithoutMovingTheVisibleAnchor() =
        runTest {
            val edge = mutableStateOf("edge-1")
            val finishFirstPage = CompletableDeferred<Unit>()
            val requests = mutableListOf<String?>()
            val collector =
                backgroundScope.launch {
                    olderPagingRequests {
                        ConversationOlderPagingDemand("visible", edge.value, true, 0L)
                    }.collect { demand ->
                        requests += demand.oldestLoadedMessageId
                        if (requests.size == 1) finishFirstPage.await()
                    }
                }
            runCurrent()
            edge.value = "edge-2"
            Snapshot.sendApplyNotifications()
            finishFirstPage.complete(Unit)
            runCurrent()
            assertEquals(listOf("edge-1", "edge-2"), requests)
            collector.cancel()
        }
}
