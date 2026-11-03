package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.ChatListBatchCommand
import dev.ipf.whitenoise.android.state.ChatListBatchOutcome
import dev.ipf.whitenoise.android.state.ChatListBatchResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatPinFollowToTopTest {
    @get:Rule val composeRule = createComposeRule()

    private val dataset = ChatListDatasetKey(showArchived = false, folderId = null, query = "", accountRef = "a")

    /** Only CHANGED outcomes of a PIN batch count; failed, unconfirmed, unstarted and already-pinned do not. */
    @Test fun onlyConfirmedPinOutcomesFollow() {
        val attempt =
            ChatListBatchResult(
                mapOf(
                    "a" to ChatListBatchOutcome.CHANGED,
                    "b" to ChatListBatchOutcome.FAILED,
                    "c" to ChatListBatchOutcome.UNCONFIRMED,
                    "d" to ChatListBatchOutcome.NOT_STARTED,
                    "e" to ChatListBatchOutcome.SATISFIED,
                ),
            )
        assertEquals(setOf("a"), newlyConfirmedPins(ChatListBatchCommand.PIN, null, attempt))
        assertEquals(emptySet<String>(), newlyConfirmedPins(ChatListBatchCommand.UNPIN, null, attempt))
        assertEquals(emptySet<String>(), newlyConfirmedPins(ChatListBatchCommand.PIN, null, null))
    }

    /** A Check that confirms an earlier unconfirmed pin follows once; re-delivering the same result does not. */
    @Test fun checkFollowsOnlyNewlyConfirmedPins() {
        val first =
            ChatListBatchResult(mapOf("a" to ChatListBatchOutcome.CHANGED, "b" to ChatListBatchOutcome.UNCONFIRMED))
        val check = ChatListBatchResult(mapOf("b" to ChatListBatchOutcome.CHANGED))
        assertEquals(setOf("b"), newlyConfirmedPins(ChatListBatchCommand.PIN, first, check))
        val merged = first.withAttempt(check)
        assertEquals(emptySet<String>(), newlyConfirmedPins(ChatListBatchCommand.PIN, merged, check))
    }

    /** Dataset or drag changes retire; a still-unpinned visible row or other motion waits; otherwise follow. */
    @Test fun stepRetiresWaitsAndFollows() {
        val request = ChatPinFollowRequest(1L, setOf("ab"), dataset, userGestureGeneration = 3L)

        /** [request]'s step with each live input defaulting to the request's own scope and a settled list. */
        fun step(
            key: ChatListDatasetKey = dataset,
            gesture: Long = 3L,
            unpinned: Set<String> = emptySet(),
            settled: Boolean = true,
        ) = chatPinFollowStep(request, key, gesture, unpinned, settled)
        assertEquals(ChatPinFollowStep.RETIRE, step(key = dataset.copy(folderId = "f")))
        assertEquals(ChatPinFollowStep.RETIRE, step(key = dataset.copy(query = "x")))
        assertEquals(ChatPinFollowStep.RETIRE, step(key = dataset.copy(accountRef = "b")))
        assertEquals(ChatPinFollowStep.RETIRE, step(gesture = 4L))
        assertEquals(ChatPinFollowStep.WAIT, step(unpinned = setOf("ab")))
        assertEquals(ChatPinFollowStep.WAIT, step(settled = false))
        assertEquals(ChatPinFollowStep.FOLLOW, step(unpinned = setOf("cd")))
    }

    /** The owner issues one request per confirmation, a newer one replaces an older, and consume is exact. */
    @Test fun ownerIssuesAndConsumesOneRequest() {
        val owner = ChatPinFollowOwner()
        owner.issue(null, setOf("AB"))
        assertNull("no captured scope, no follow", owner.request)
        val scope = ChatPinFollowScope(dataset, 0L)
        owner.issue(scope, emptySet())
        assertNull(owner.request)
        owner.issue(scope, setOf("AB"))
        val first = owner.request!!
        assertEquals(setOf("ab"), first.chatIds)
        owner.issue(scope, setOf("cd"))
        val second = owner.request!!
        owner.consume(first)
        assertEquals("a stale consume must not drop the newer request", second, owner.request)
        owner.consume(second)
        assertNull(owner.request)
    }

    /**
     * A bounded window that has rows before its front: the follow scrolls, returns the window to its
     * head, and settles at the absolute first item with zero offset, consuming the request once. A backward
     * page changes the rows first, and the restored head is published several frames later, as the
     * controller's debounced recompute does, so only the installed head frame may release the snap.
     */
    @Test fun shiftedWindowFollowRestoresHeadWindowAndSettlesAtZero() {
        val head = List(60) { "row-$it" }
        var rows by mutableStateOf(head.drop(30))
        var request by mutableStateOf<ChatPinFollowRequest?>(null)
        var windowReturns = 0
        val consumed = mutableListOf<ChatPinFollowRequest>()
        lateinit var listState: LazyListState
        composeRule.setContent {
            listState = rememberLazyListState(initialFirstVisibleItemIndex = 20)
            val publisher = rememberCoroutineScope()
            ChatPinFollowToTopEffect(
                listState = listState,
                request = request,
                datasetKey = dataset,
                userGestureGeneration = 0L,
                unpinnedVisibleIds = emptySet(),
                motionSettled = true,
                hasEarlierRows = { rows.first() != head.first() },
                returnWindowToTop = {
                    windowReturns += 1
                    // A backward page published while the return was in flight: new rows, still not the head.
                    rows = head.drop(28)
                    publisher.launch {
                        delay(DEBOUNCED_PUBLICATION_MILLIS)
                        rows = head
                    }
                    ChatPinFollowHeadPresented { rows == head }
                },
                onScrollStarted = {},
                onConsumed = {
                    consumed += it
                    if (request == it) request = null
                },
            )
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(400.dp)) {
                items(rows, key = { it }) { Box(Modifier.fillMaxWidth().height(48.dp)) { Text(it) } }
            }
        }
        composeRule.waitForIdle()
        val issued = ChatPinFollowRequest(1L, setOf("row-0"), dataset, 0L)
        issue { request = issued }
        awaitConsumed(consumed)
        composeRule.waitUntil(5_000) { rows == head }
        composeRule.waitForIdle()
        assertEquals(listOf(issued), consumed)
        assertEquals(1, windowReturns)
        assertEquals(head, rows)
        composeRule.runOnIdle {
            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
            assertEquals(
                "row-0",
                listState.layoutInfo.visibleItemsInfo
                    .first()
                    .key,
            )
        }
    }

    /** A request whose dataset changed before it could follow is retired without moving the viewport. */
    @Test fun retiredRequestDoesNotScroll() {
        var request by mutableStateOf<ChatPinFollowRequest?>(null)
        val consumed = mutableListOf<ChatPinFollowRequest>()
        lateinit var listState: LazyListState
        composeRule.setContent {
            listState = rememberLazyListState(initialFirstVisibleItemIndex = 20)
            ChatPinFollowToTopEffect(
                listState = listState,
                request = request,
                datasetKey = dataset.copy(folderId = "other"),
                userGestureGeneration = 0L,
                unpinnedVisibleIds = emptySet(),
                motionSettled = true,
                hasEarlierRows = { false },
                returnWindowToTop = { error("a retired request must not move the window") },
                onScrollStarted = {},
                onConsumed = { consumed += it },
            )
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(400.dp)) {
                items(List(60) { "row-$it" }, key = { it }) { Box(Modifier.fillMaxWidth().height(48.dp)) { Text(it) } }
            }
        }
        composeRule.waitForIdle()
        issue { request = ChatPinFollowRequest(1L, setOf("row-0"), dataset, 0L) }
        awaitConsumed(consumed)
        composeRule.runOnIdle { assertTrue(listState.firstVisibleItemIndex >= 20) }
    }

    /**
     * MDK answers the first return stale, leaving the window shifted: the follow retries the return while it still
     * owns the viewport and settles on the real head, not on the first row of the shifted window.
     */
    @Test fun staleReturnIsRetriedUntilTheHeadIsShown() {
        val head = List(60) { "row-$it" }
        var rows by mutableStateOf(head.drop(30))
        var request by mutableStateOf<ChatPinFollowRequest?>(null)
        var windowReturns = 0
        val consumed = mutableListOf<ChatPinFollowRequest>()
        lateinit var listState: LazyListState
        composeRule.setContent {
            listState = rememberLazyListState(initialFirstVisibleItemIndex = 20)
            ChatPinFollowToTopEffect(
                listState = listState,
                request = request,
                datasetKey = dataset,
                userGestureGeneration = 0L,
                unpinnedVisibleIds = emptySet(),
                motionSettled = true,
                hasEarlierRows = { rows.first() != head.first() },
                returnWindowToTop = {
                    windowReturns += 1
                    // The first answer is stale and installs nothing; its current frame is already presented.
                    if (windowReturns > 1) rows = head
                    ChatPinFollowHeadPresented { true }
                },
                onScrollStarted = {},
                onConsumed = {
                    consumed += it
                    if (request == it) request = null
                },
            )
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(400.dp)) {
                items(rows, key = { it }) { Box(Modifier.fillMaxWidth().height(48.dp)) { Text(it) } }
            }
        }
        composeRule.waitForIdle()
        issue { request = ChatPinFollowRequest(1L, setOf("row-0"), dataset, 0L) }
        awaitConsumed(consumed)
        composeRule.waitForIdle()
        assertEquals(2, windowReturns)
        composeRule.runOnIdle {
            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
            assertEquals(
                "row-0",
                listState.layoutInfo.visibleItemsInfo
                    .first()
                    .key,
            )
        }
    }

    /**
     * A folder switch while the follow is animating retires it at once: the old pin never returns the replacement
     * list's window and never snaps it, and the request is still consumed.
     */
    @Test fun datasetChangeMidAnimationStopsTheFollow() {
        var datasetKey by mutableStateOf(dataset)
        var request by mutableStateOf<ChatPinFollowRequest?>(null)
        var windowReturns = 0
        var snapsAfterSwitch = 0
        val consumed = mutableListOf<ChatPinFollowRequest>()
        lateinit var listState: LazyListState
        composeRule.setContent {
            listState = rememberLazyListState(initialFirstVisibleItemIndex = 8)
            ChatPinFollowToTopEffect(
                listState = listState,
                request = request,
                datasetKey = datasetKey,
                userGestureGeneration = 0L,
                unpinnedVisibleIds = emptySet(),
                motionSettled = true,
                hasEarlierRows = { true },
                returnWindowToTop = {
                    windowReturns += 1
                    ChatPinFollowHeadPresented { true }
                },
                onScrollStarted = { if (datasetKey != dataset) snapsAfterSwitch += 1 },
                onConsumed = { consumed += it },
            )
            LaunchedEffect(listState) {
                // The reader switches folders once the follow's animation is under way.
                snapshotFlow { listState.isScrollInProgress }.first { it }
                datasetKey = dataset.copy(folderId = "other")
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(400.dp)) {
                items(List(60) { "row-$it" }, key = { it }) { Box(Modifier.fillMaxWidth().height(48.dp)) { Text(it) } }
            }
        }
        composeRule.waitForIdle()
        issue { request = ChatPinFollowRequest(1L, setOf("row-0"), dataset, 0L) }
        awaitConsumed(consumed)
        composeRule.waitForIdle()
        assertEquals("the switched dataset must already be live", dataset.copy(folderId = "other"), datasetKey)
        assertEquals("a retired follow must not return the replacement window", 0, windowReturns)
        assertEquals("a retired follow must not snap the replacement list", 0, snapsAfterSwitch)
    }

    /** Writes test state on the UI thread and publishes it, as a production snapshot write would be. */
    private fun issue(write: () -> Unit) {
        composeRule.runOnIdle {
            write()
            Snapshot.sendApplyNotifications()
        }
    }

    /** Drives frames and Android Main until the effect reports a consumed request. */
    private fun awaitConsumed(consumed: List<ChatPinFollowRequest>) {
        composeRule.waitUntil(5_000) {
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            consumed.isNotEmpty()
        }
    }

    private companion object {
        /** Longer than two frames: the presentation lifetime's 16 ms debounce plus its Main hop. */
        const val DEBOUNCED_PUBLICATION_MILLIS = 80L
    }
}
