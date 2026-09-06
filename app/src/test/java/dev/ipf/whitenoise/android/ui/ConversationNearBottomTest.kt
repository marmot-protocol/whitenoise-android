package dev.ipf.whitenoise.android.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.ConversationForegroundGeometry
import dev.ipf.whitenoise.android.ui.conversation.ConversationForegroundRestoreToken
import dev.ipf.whitenoise.android.ui.conversation.ConversationForegroundSnapshot
import dev.ipf.whitenoise.android.ui.conversation.ConversationJumpToNewestButton
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollAnchor
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollCoordinator
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollMode
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollReason
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollWriter
import dev.ipf.whitenoise.android.ui.conversation.ConversationTimelineStructure
import dev.ipf.whitenoise.android.ui.conversation.LazyListConversationScrollWriter
import dev.ipf.whitenoise.android.ui.conversation.conversationScrollAnchor
import dev.ipf.whitenoise.android.ui.conversation.conversationTimelineTailListIndex
import dev.ipf.whitenoise.android.ui.conversation.isNearBottom
import dev.ipf.whitenoise.android.ui.conversation.jumpToNewest
import dev.ipf.whitenoise.android.ui.conversation.rememberConversationNearBottom
import kotlinx.coroutines.launch
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
        tailRowHeight: Dp = 50.dp,
    ) {
        LazyColumn(modifier = modifier, state = listState) {
            item { Spacer(Modifier.height(1.dp)) }
            item { Spacer(Modifier.height(1.dp)) }
            items((0 until timelineSize).toList()) { index ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(if (index == timelineSize - 1) tailRowHeight else 50.dp)
                        .then(
                            if (index == timelineSize - 1) {
                                Modifier.testTag(TAIL_ROW_TAG)
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }
    }

    @Composable
    private fun TallTailHarness(
        listState: LazyListState,
        timelineSize: Int,
        coordinatorHolder: Array<ConversationScrollCoordinator?>,
    ) {
        val tailTimelineIndex = requireNotNull(conversationTimelineTailListIndex(timelineSize, 1))
        val coordinator =
            remember(listState) {
                ConversationScrollCoordinator(
                    writer = LazyListConversationScrollWriter(listState),
                    initialMode = ConversationScrollMode.ReadingHistory("tail", 0),
                )
            }
        val scope = rememberCoroutineScope()
        coordinatorHolder[0] = coordinator
        val nearBottom =
            rememberConversationNearBottom(
                listState = listState,
                renderedTimelineSize = timelineSize,
                hasOlderHeader = true,
            )

        Box {
            TimelineHarness(
                listState = listState,
                timelineSize = timelineSize,
                modifier = Modifier.height(100.dp),
                tailRowHeight = 400.dp,
            )
            if (!nearBottom) {
                ConversationJumpToNewestButton(
                    unreadIncomingCount = 0,
                    onClick = {
                        scope.launch {
                            coordinator.jumpToNewest(tailTimelineIndex)
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
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
    fun shortTailRemainsNearBottomWhileItIsStillVisible() {
        val listState = LazyListState()
        val timelineSize = 3
        val firstTimelineIndex = 2
        val tailListIndex =
            requireNotNull(conversationTimelineTailListIndex(timelineSize, leadingStructuralRowCount = 1))

        composeRule.setContent {
            TimelineHarness(
                listState = listState,
                timelineSize = timelineSize,
                modifier = Modifier.height(100.dp),
            )
        }
        composeRule.waitForIdle()

        val rowSize =
            listState.layoutInfo.visibleItemsInfo
                .single { it.index == firstTimelineIndex }
                .size
        scrollTo(listState, firstTimelineIndex, rowSize * 2 / 5)

        val layoutInfo = listState.layoutInfo
        val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
        val lastVisible = layoutInfo.visibleItemsInfo.last()
        val tailDistanceFromViewport =
            lastVisible.offset + lastVisible.size - layoutInfo.viewportEndOffset

        assertEquals(tailListIndex, lastVisible.index)
        assertTrue(lastVisible.size < viewportHeight)
        assertTrue(tailDistanceFromViewport > viewportHeight / 4)
        assertTrue(tailDistanceFromViewport <= lastVisible.size)
        assertTrue(
            "A normal tail row remains near-bottom while any part is visible",
            isNearBottom(listState, timelineSize, hasOlderHeader = true),
        )
    }

    @Test
    fun tallTailShowsJumpButtonBeforeItsBodyLeavesTheViewport() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val jumpToNewestLabel = context.getString(R.string.jump_to_newest)
        val listState = LazyListState()
        val timelineSize = 1
        val tailTimelineIndex = requireNotNull(conversationTimelineTailListIndex(timelineSize, 1))
        val coordinatorHolder = arrayOf<ConversationScrollCoordinator?>(null)

        composeRule.setContent {
            TallTailHarness(listState, timelineSize, coordinatorHolder)
        }

        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            runBlocking {
                coordinatorHolder[0]!!.programmaticJump(
                    targetMessageId = null,
                    reason = ConversationScrollReason.JumpToNewest,
                    resultingMode = ConversationScrollMode.FollowingTail,
                ) {
                    scrollToTail(tailTimelineIndex)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(jumpToNewestLabel).assertDoesNotExist()

        val viewportHeight =
            listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
        val tailSize =
            listState.layoutInfo.visibleItemsInfo
                .single { it.index == tailTimelineIndex }
                .size
        val nearTailOffset = tailSize - viewportHeight - viewportHeight / 8
        scrollTo(listState, tailTimelineIndex, nearTailOffset)
        val nearTail = listState.layoutInfo.visibleItemsInfo.last()
        val nearTailDistanceFromViewport =
            nearTail.offset + nearTail.size - listState.layoutInfo.viewportEndOffset

        assertEquals(tailTimelineIndex, nearTail.index)
        assertTrue(nearTailDistanceFromViewport in 1 until viewportHeight / 4)
        composeRule.onNodeWithTag(TAIL_ROW_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(jumpToNewestLabel).assertDoesNotExist()

        val farTailOffset = tailSize - viewportHeight - viewportHeight / 2
        scrollTo(listState, tailTimelineIndex, farTailOffset)
        val lastVisible = listState.layoutInfo.visibleItemsInfo.last()
        val tailDistanceFromViewport =
            lastVisible.offset + lastVisible.size - listState.layoutInfo.viewportEndOffset
        assertEquals(tailTimelineIndex, lastVisible.index)
        assertTrue(tailDistanceFromViewport > viewportHeight / 4)
        composeRule.onNodeWithTag(TAIL_ROW_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(jumpToNewestLabel).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(jumpToNewestLabel).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TAIL_ROW_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(jumpToNewestLabel).assertDoesNotExist()
        composeRule.runOnIdle {
            assertFalse(
                "Jump to newest must reach the physical end of the list",
                listState.canScrollForward,
            )
            assertTrue(coordinatorHolder[0]!!.isFollowingTail)
        }
    }

    @Test
    fun jumpToNewestWithUnreadAndOffscreenReadAnchorReachesTailInOneTap() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val jumpToNewestLabel = context.getString(R.string.jump_to_newest)
        val listState = LazyListState()
        val timelineSize = 50
        val tailTimelineIndex =
            requireNotNull(conversationTimelineTailListIndex(timelineSize, leadingStructuralRowCount = 1))
        val coordinatorHolder = arrayOf<ConversationScrollCoordinator?>(null)

        composeRule.setContent {
            val coordinator =
                remember(listState) {
                    ConversationScrollCoordinator(
                        writer = LazyListConversationScrollWriter(listState),
                        initialMode = ConversationScrollMode.ReadingHistory("read-anchor", 0),
                    )
                }
            val scope = rememberCoroutineScope()
            coordinatorHolder[0] = coordinator
            val nearBottom =
                rememberConversationNearBottom(
                    listState = listState,
                    renderedTimelineSize = timelineSize,
                    hasOlderHeader = true,
                )

            Box {
                TimelineHarness(
                    listState = listState,
                    timelineSize = timelineSize,
                    modifier = Modifier.height(100.dp),
                )
                if (!nearBottom) {
                    ConversationJumpToNewestButton(
                        unreadIncomingCount = 3,
                        onClick = {
                            scope.launch {
                                coordinator.jumpToNewest(tailTimelineIndex)
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(jumpToNewestLabel).assertIsDisplayed()
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.onNodeWithTag(TAIL_ROW_TAG).assertDoesNotExist()

        composeRule.onNodeWithContentDescription(jumpToNewestLabel).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TAIL_ROW_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(jumpToNewestLabel).assertDoesNotExist()
        composeRule.runOnIdle {
            assertTrue(coordinatorHolder[0]!!.isFollowingTail)
        }
    }

    @Test
    fun pausingInsideATallTailPreservesTheOffsetOnResumeWithTheKeyboardClosed() {
        assertTallTailResumePreservesOffset(resumeViewportHeight = TALL_TAIL_VIEWPORT)
    }

    @Test
    fun pausingInsideATallTailPreservesTheOffsetOnResumeWithTheKeyboardOpen() {
        assertTallTailResumePreservesOffset(resumeViewportHeight = 60.dp)
    }

    /**
     * Drives the production pause/resume path: real [isNearBottom] feeds
     * [ConversationScrollCoordinator.onUserGestureSettled], the settled mode is
     * bookmarked, and the foreground transaction must land back on the same pixel.
     */
    @Suppress("LongMethod") // Real LazyList pause, relayout, and resume must stay in one scenario.
    private fun assertTallTailResumePreservesOffset(resumeViewportHeight: Dp) {
        val listState = LazyListState()
        val timelineSize = 5
        val tailListIndex =
            requireNotNull(conversationTimelineTailListIndex(timelineSize, leadingStructuralRowCount = 1))
        val itemIds = (0 until timelineSize).map { "item-$it" }
        val messageIds = (0 until timelineSize).map { "message-$it" }
        val viewportHeight = mutableStateOf(TALL_TAIL_VIEWPORT)

        composeRule.setContent {
            TimelineHarness(
                listState = listState,
                timelineSize = timelineSize,
                modifier = Modifier.height(viewportHeight.value),
                tailRowHeight = 600.dp,
            )
        }
        composeRule.waitForIdle()
        scrollTo(listState, tailListIndex, PAUSED_OFFSET_IN_TALL_TAIL)

        val nearBottom = isNearBottom(listState, timelineSize, hasOlderHeader = true)
        assertTrue(
            "Unread pixels must remain below the viewport for this to be a history read",
            listState.canScrollForward,
        )
        assertFalse(
            "A reader partway through a tall newest message is not at the tail",
            nearBottom,
        )

        val anchor =
            conversationScrollAnchor(
                listState = listState,
                renderedItemIds = itemIds,
                renderedMessageIds = messageIds,
                hasOlderHeader = true,
            )
        val coordinator =
            ConversationScrollCoordinator(writer = LazyListConversationScrollWriter(listState))
        coordinator.onUserGestureSettled(anchor, nearBottom)
        assertFalse("Gesture settlement must not claim tail-follow intent", coordinator.isFollowingTail)

        val snapshot = coordinator.bookmark(anchor)
        val pausedGeometry = ConversationForegroundGeometry(listState.layoutInfo.viewportSize.height, 0, 96)
        val foregroundToken =
            coordinator.beginForegroundRestore(
                ConversationForegroundSnapshot(
                    scrollBookmark = snapshot,
                    geometry = pausedGeometry,
                    timelineStructure =
                        ConversationTimelineStructure(
                            rowKeys = itemIds.zip(messageIds),
                            olderHeaderCount = 1,
                        ),
                ),
            )
        // Models a new message landing while backgrounded plus the resume relayout.
        scrollTo(listState, 2)
        composeRule.runOnUiThread { viewportHeight.value = resumeViewportHeight }
        composeRule.waitForIdle()
        restoreOnResume(coordinator, foregroundToken, listState, itemIds, messageIds)

        assertEquals("message-${timelineSize - 1}", snapshot.anchor.messageId)
        assertEquals(tailListIndex, listState.firstVisibleItemIndex)
        assertEquals(
            "Resume must preserve the pixel offset inside the long message",
            PAUSED_OFFSET_IN_TALL_TAIL,
            listState.firstVisibleItemScrollOffset,
        )
        assertTrue("The reader must still see unread pixels below", listState.canScrollForward)
        assertFalse(coordinator.isFollowingTail)
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
        val geometry = ConversationForegroundGeometry(listState.layoutInfo.viewportSize.height, 0, 96)
        val structure = ConversationTimelineStructure(listOf("msg:reader" to "reader"), 1)
        val token =
            coordinator.beginForegroundRestore(
                ConversationForegroundSnapshot(
                    scrollBookmark =
                        coordinator.bookmark(
                            ConversationScrollAnchor(indexBefore, offsetBefore, "msg:reader", "reader"),
                        ),
                    geometry = geometry,
                    timelineStructure = structure,
                ),
            )

        composeRule.runOnUiThread {
            runBlocking {
                coordinator.completeForegroundRestore(
                    token = token,
                    resumedGeometry = geometry,
                    resumedTimelineStructure = structure,
                    resolveAnchorIndex = { indexBefore },
                    resolveTailIndex = { listState.layoutInfo.totalItemsCount - 1 },
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals(indexBefore, listState.firstVisibleItemIndex)
        assertEquals(offsetBefore, listState.firstVisibleItemScrollOffset)
        assertFalse(coordinator.isFollowingTail)
    }

    @Test
    fun repeatedUnchangedForegroundHandoffsPerformZeroLazyListWritesOrDrift() {
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
        val writer = RecordingLazyListWriter(listState)
        val coordinator =
            ConversationScrollCoordinator(
                writer = writer,
                initialMode = ConversationScrollMode.ReadingHistory("reader", offsetBefore),
            )
        val anchor = ConversationScrollAnchor(indexBefore, offsetBefore, "msg:reader", "reader")
        val geometry = ConversationForegroundGeometry(listState.layoutInfo.viewportSize.height, 0, 96)
        val structure =
            ConversationTimelineStructure(
                rowKeys = (0 until 50).map { index -> "item-$index" to "message-$index" },
                olderHeaderCount = 1,
            )

        repeat(20) {
            composeRule.runOnUiThread {
                runBlocking {
                    val token =
                        coordinator.beginForegroundRestore(
                            ConversationForegroundSnapshot(
                                scrollBookmark = coordinator.bookmark(anchor),
                                geometry = geometry,
                                timelineStructure = structure,
                            ),
                        )
                    coordinator.completeForegroundRestore(
                        token = token,
                        resumedGeometry = geometry,
                        resumedTimelineStructure = structure,
                        resumedScrollAnchor = anchor,
                        resolveAnchorIndex = { indexBefore },
                        resolveTailIndex = { listState.layoutInfo.totalItemsCount - 1 },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        assertEquals(0, writer.writeCount)
        assertEquals(indexBefore, listState.firstVisibleItemIndex)
        assertEquals(offsetBefore, listState.firstVisibleItemScrollOffset)
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
        val pausedGeometry = ConversationForegroundGeometry(listState.layoutInfo.viewportSize.height, 0, 96)
        val structure = ConversationTimelineStructure(listOf("msg:last" to "last"), 1)
        val token =
            coordinator.beginForegroundRestore(
                ConversationForegroundSnapshot(
                    scrollBookmark =
                        coordinator.bookmark(
                            ConversationScrollAnchor(tail, 0, "msg:last", "last"),
                        ),
                    geometry = pausedGeometry,
                    timelineStructure = structure,
                ),
            )

        // Models a transient viewport relayout before the IME settles.
        scrollTo(listState, 20)
        composeRule.runOnUiThread {
            runBlocking {
                coordinator.completeForegroundRestore(
                    token = token,
                    resumedGeometry = pausedGeometry.copy(viewportHeightPx = pausedGeometry.viewportHeightPx + 1),
                    resumedTimelineStructure = structure,
                    resolveAnchorIndex = { tail },
                    resolveTailIndex = { tail },
                )
            }
        }
        composeRule.waitForIdle()

        assertTrue(isNearBottom(listState, timelineSize = 50, hasOlderHeader = true))
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

    /** Mirrors the resume observer: resolve the bookmarked message back to a live list index. */
    private fun restoreOnResume(
        coordinator: ConversationScrollCoordinator,
        token: ConversationForegroundRestoreToken,
        listState: LazyListState,
        itemIds: List<String>,
        messageIds: List<String>,
    ) {
        composeRule.runOnUiThread {
            runBlocking {
                coordinator.completeForegroundRestore(
                    token = token,
                    resumedGeometry =
                        ConversationForegroundGeometry(
                            viewportHeightPx = listState.layoutInfo.viewportSize.height,
                            imeBottomPx = 0,
                            bottomChromeHeightPx = 96,
                        ),
                    resumedTimelineStructure =
                        ConversationTimelineStructure(
                            rowKeys = itemIds.zip(messageIds),
                            olderHeaderCount = 1,
                        ),
                    resumedScrollAnchor =
                        conversationScrollAnchor(
                            listState = listState,
                            renderedItemIds = itemIds,
                            renderedMessageIds = messageIds,
                            hasOlderHeader = true,
                        ),
                    resolveAnchorIndex = { anchor ->
                        messageIds.indexOf(anchor.messageId).takeIf { it >= 0 }?.plus(2)
                    },
                    resolveTailIndex = { listState.layoutInfo.totalItemsCount - 1 },
                )
            }
        }
        composeRule.waitForIdle()
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

    private class RecordingLazyListWriter(
        private val state: LazyListState,
    ) : ConversationScrollWriter {
        var writeCount = 0
            private set
        override val firstVisibleItemIndex: Int
            get() = state.firstVisibleItemIndex

        override suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            writeCount++
            state.scrollToItem(index, scrollOffset)
        }

        override suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            writeCount++
            state.animateScrollToItem(index, scrollOffset)
        }
    }
}

private const val TAIL_ROW_TAG = "conversation-tail-row"
private val TALL_TAIL_VIEWPORT = 100.dp
private const val PAUSED_OFFSET_IN_TALL_TAIL = 200
