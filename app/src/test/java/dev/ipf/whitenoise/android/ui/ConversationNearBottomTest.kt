package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollAnchor
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollCoordinator
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollMode
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollReason
import dev.ipf.whitenoise.android.ui.conversation.LazyListConversationScrollWriter
import dev.ipf.whitenoise.android.ui.conversation.conversationScrollAnchor
import dev.ipf.whitenoise.android.ui.conversation.isNearBottom
import dev.ipf.whitenoise.android.ui.conversation.rememberConversationNearBottom
import dev.ipf.whitenoise.android.ui.conversation.restoreViewport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pins real LazyListState integration for conversation scroll intent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationNearBottomTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Composable
    private fun TimelineHarness(
        listState: LazyListState,
        timelineSize: Int,
        modifier: Modifier = Modifier.height(400.dp),
    ) {
        LazyColumn(modifier = modifier, state = listState) {
            item { Spacer(Modifier.height(1.dp)) }
            item { Spacer(Modifier.height(1.dp)) }
            items((0 until timelineSize).toList()) {
                Box(Modifier.fillMaxWidth().height(50.dp))
            }
            item { Spacer(Modifier.height(1.dp)) }
        }
    }

    @Test
    fun nearBottomTracksTimelineHydrationWhenScrolledUp() {
        val listState = LazyListState()
        val timelineSize = mutableStateOf(0)
        val nearBottomHolder = arrayOf<Boolean?>(null)

        composeRule.setContent {
            nearBottomHolder[0] =
                rememberConversationNearBottom(
                    listState = listState,
                    renderedTimelineSize = timelineSize.value,
                    hasOlderHeader = true,
                )
            TimelineHarness(
                listState = listState,
                timelineSize = 50,
                modifier = Modifier.height(100.dp),
            )
        }

        composeRule.waitForIdle()
        composeRule.runOnUiThread { timelineSize.value = 50 }
        composeRule.waitForIdle()
        scrollTo(listState, 20)

        composeRule.runOnUiThread {
            assertFalse(
                "Jump FAB should show when scrolled up after timeline hydration",
                nearBottomHolder[0]!!,
            )
        }
    }

    @Test
    fun readingHistoryViewportRestoreKeepsTheSameListAnchorAndPixelOffset() {
        val listState = LazyListState()
        composeRule.setContent {
            TimelineHarness(
                listState = listState,
                timelineSize = 50,
                modifier = Modifier.height(100.dp),
            )
        }
        composeRule.waitForIdle()
        scrollTo(listState, 20, 17)
        val indexBefore = listState.firstVisibleItemIndex
        val offsetBefore = listState.firstVisibleItemScrollOffset
        val coordinator =
            ConversationScrollCoordinator(
                writer = LazyListConversationScrollWriter(listState),
                initialMode = ConversationScrollMode.ReadingHistory("reader", offsetBefore),
            )
        val snapshot =
            coordinator.bookmark(
                ConversationScrollAnchor(indexBefore, offsetBefore, "msg:reader", "reader"),
            )

        composeRule.runOnUiThread {
            runBlocking {
                coordinator.restoreViewport(
                    snapshot = snapshot,
                    resolveAnchorIndex = { indexBefore },
                    resolveTailIndex = { listState.layoutInfo.totalItemsCount - 1 },
                    frameCount = 1,
                    awaitFrame = {},
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals(indexBefore, listState.firstVisibleItemIndex)
        assertEquals(offsetBefore, listState.firstVisibleItemScrollOffset)
        assertFalse(coordinator.isFollowingTail)
    }

    @Test
    fun followingTailViewportRestoreReanchorsAfterViewportMovement() {
        val listState = LazyListState()
        composeRule.setContent {
            TimelineHarness(
                listState = listState,
                timelineSize = 50,
                modifier = Modifier.height(100.dp),
            )
        }
        composeRule.waitForIdle()
        val tail = listState.layoutInfo.totalItemsCount - 1
        scrollTo(listState, tail)
        val coordinator = ConversationScrollCoordinator(LazyListConversationScrollWriter(listState))
        val snapshot =
            coordinator.bookmark(
                ConversationScrollAnchor(tail, 0, "msg:last", "last"),
            )

        // Models a transient viewport relayout before the IME settles.
        scrollTo(listState, 20)
        composeRule.runOnUiThread {
            runBlocking {
                coordinator.restoreViewport(
                    snapshot = snapshot,
                    resolveAnchorIndex = { tail },
                    resolveTailIndex = { tail },
                    frameCount = 3,
                    awaitFrame = {},
                )
            }
        }
        composeRule.waitForIdle()

        assertTrue(isNearBottom(listState, timelineSize = 50, hasOlderHeader = false))
        assertTrue(coordinator.isFollowingTail)
    }

    @Test
    fun newMessageDoesNotMoveAHistoryReader() {
        val listState = LazyListState()
        val timelineSize = mutableStateOf(50)
        composeRule.setContent {
            TimelineHarness(
                listState = listState,
                timelineSize = timelineSize.value,
                modifier = Modifier.height(100.dp),
            )
        }
        composeRule.waitForIdle()
        scrollTo(listState, 20, 9)
        val indexBefore = listState.firstVisibleItemIndex
        val offsetBefore = listState.firstVisibleItemScrollOffset
        val coordinator =
            ConversationScrollCoordinator(
                writer = LazyListConversationScrollWriter(listState),
                initialMode = ConversationScrollMode.ReadingHistory("reader", offsetBefore),
            )

        composeRule.runOnUiThread { timelineSize.value = 51 }
        composeRule.waitForIdle()
        var followed = true
        composeRule.runOnUiThread {
            runBlocking {
                followed =
                    coordinator.followTailIfAllowed(
                        resolveTailIndex = { listState.layoutInfo.totalItemsCount - 1 },
                        reason = ConversationScrollReason.NewMessage,
                        awaitFrame = {},
                    )
            }
        }
        composeRule.waitForIdle()

        assertFalse(followed)
        assertEquals(indexBefore, listState.firstVisibleItemIndex)
        assertEquals(offsetBefore, listState.firstVisibleItemScrollOffset)
    }

    @Test
    fun headerFirstViewportAnchorsTheFirstVisibleTimelineRow() {
        val listState = LazyListState()
        val itemIds = (0 until 50).map { "item-$it" }
        val messageIds = (0 until 50).map { "message-$it" }
        composeRule.setContent {
            TimelineHarness(
                listState = listState,
                timelineSize = 50,
                modifier = Modifier.height(100.dp),
            )
        }
        composeRule.waitForIdle()
        scrollTo(listState, 1)
        val firstTimelineRow = listState.layoutInfo.visibleItemsInfo.first { it.index >= 2 }

        val anchor =
            conversationScrollAnchor(
                listState = listState,
                renderedItemIds = itemIds,
                renderedMessageIds = messageIds,
                hasOlderHeader = true,
            )

        assertEquals(1, listState.firstVisibleItemIndex)
        assertEquals(firstTimelineRow.index, anchor.listIndex)
        assertEquals(-firstTimelineRow.offset, anchor.pixelOffset)
        assertEquals("item-0", anchor.itemId)
        assertEquals("message-0", anchor.messageId)
    }

    private fun scrollTo(
        listState: LazyListState,
        index: Int,
        offset: Int = 0,
    ) {
        composeRule.runOnUiThread {
            runBlocking { listState.scrollToItem(index, offset) }
        }
        composeRule.waitForIdle()
    }
}
