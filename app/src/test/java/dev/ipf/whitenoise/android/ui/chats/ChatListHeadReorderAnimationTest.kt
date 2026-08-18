package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Motion regression for issue #1651: keyed head reorder must animate row
 * placement and pair it with animated scroll correction instead of hard-snapping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatListHeadReorderAnimationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val rowHeight = 48.dp

    @Test
    fun headReorderAnimatesRowsInOppositeDirectionsAndFinishesFlushAtTop() {
        var itemIds by mutableStateOf(listOf("A", "B"))
        val listStateHolder = arrayOf<LazyListState?>(null)

        composeRule.setContent {
            val listState = rememberLazyListState()
            listStateHolder[0] = listState
            ChatListHeadReorderMotionHarness(
                itemIds = itemIds,
                listState = listState,
                rowHeight = rowHeight,
            )
        }

        composeRule.waitForIdle()
        val rowHeightPx = composeRule.density.run { rowHeight.toPx() }
        val aStartTop = rowTop("A")
        val bStartTop = rowTop("B")
        assertEquals(0f, aStartTop, 0.5f)
        assertEquals(rowHeightPx, bStartTop, 0.5f)

        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread {
            itemIds = listOf("B", "A")
        }
        composeRule.runOnIdle { }

        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle {
            assertTrue(
                "head correction should still be scrolling on the first animated frame",
                listStateHolder[0]!!.isScrollInProgress,
            )
        }
        val bFirstFrameTop = rowTop("B")
        assertTrue(
            "B should not already be flush at top on the first animated frame",
            bFirstFrameTop > 1f,
        )

        var aMidTop = aStartTop
        var bMidTop = bStartTop
        var sawIntermediate = false
        for (frame in 0 until 30) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { }
            aMidTop = rowTop("A")
            bMidTop = rowTop("B")
            val aIsBetweenSlots = aMidTop > 1f && aMidTop < rowHeightPx - 1f
            val bIsBetweenSlots = bMidTop > 1f && bMidTop < rowHeightPx - 1f
            if (aIsBetweenSlots && bIsBetweenSlots) {
                sawIntermediate = true
                break
            }
        }
        assertTrue("A and B should pass through in-between slots mid-animation", sawIntermediate)
        assertTrue("A should move down during reorder", aMidTop > aStartTop + 1f)
        assertTrue("B should move up during reorder", bMidTop < bStartTop - 1f)

        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.runOnIdle { }

        assertEquals(0f, rowTop("B"), 0.5f)
        assertEquals(rowHeightPx, rowTop("A"), 0.5f)
    }

    @Test
    fun folderDatasetTransitionDoesNotTriggerHeadScrollCorrection() {
        var itemIds by mutableStateOf(listOf("A", "B", "C"))
        var datasetKey by mutableStateOf("folder-a")
        val listStateHolder = arrayOf<LazyListState?>(null)

        composeRule.setContent {
            val key = ChatListDatasetKey(showArchived = false, folderId = datasetKey, query = "")
            val listState = rememberLazyListState()
            listStateHolder[0] = listState
            ChatListHeadReorderMotionHarness(
                itemIds = itemIds,
                listState = listState,
                rowHeight = rowHeight,
                datasetKey = key,
            )
        }

        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread {
            // Folder A -> B: C and A are shared and reordered, D enters,
            // while B leaves. This is not a same-dataset head promotion.
            itemIds = listOf("C", "D", "A")
            datasetKey = "folder-b"
        }
        composeRule.runOnIdle { }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle {
            assertFalse(
                "folder membership changes must not launch head-promotion scrolling",
                listStateHolder[0]!!.isScrollInProgress,
            )
        }
        val rowHeightPx = composeRule.density.run { rowHeight.toPx() }
        var sawSharedRowInMotion = false
        for (frame in 0 until 30) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { }
            val cTop = rowTop("C")
            if (cTop > 0f && cTop < rowHeightPx * 2) {
                sawSharedRowInMotion = true
                break
            }
        }
        assertTrue("shared row C snapped instead of moving into place", sawSharedRowInMotion)

        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.runOnIdle { }
        assertEquals(0f, rowTop("C"), 0.5f)
        assertEquals(rowHeightPx, rowTop("D"), 0.5f)
        assertEquals(rowHeightPx * 2, rowTop("A"), 0.5f)
    }

    @Test
    fun folderDatasetTransitionPreservesValidScrollAnchor() {
        val tail = (0 until 20).map { "E$it" }
        var itemIds by mutableStateOf(listOf("A", "B", "C") + tail)
        var datasetKey by mutableStateOf("folder-a")
        val listStateHolder = arrayOf<LazyListState?>(null)

        composeRule.setContent {
            val listState = rememberLazyListState()
            listStateHolder[0] = listState
            ChatListHeadReorderMotionHarness(
                itemIds = itemIds,
                listState = listState,
                rowHeight = rowHeight,
                datasetKey = ChatListDatasetKey(false, datasetKey, ""),
            )
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle { listStateHolder[0]!!.requestScrollToItem(1) }
        composeRule.waitForIdle()
        assertEquals(0f, rowTop("B"), 0.5f)

        composeRule.runOnUiThread {
            itemIds = listOf("X", "Y", "B", "C") + tail
            datasetKey = "folder-b"
        }
        composeRule.waitForIdle()

        assertEquals(2, listStateHolder[0]!!.firstVisibleItemIndex)
        assertEquals(0f, rowTop("B"), 0.5f)
        assertFalse(listStateHolder[0]!!.isScrollInProgress)
    }

    @Test
    fun sameDatasetMembershipTransitionRanksTargetHeadAboveCrossingRows() {
        var itemIds by mutableStateOf(listOf("A", "B", "C"))

        composeRule.setContent {
            ChatListHeadReorderMotionHarness(
                itemIds = itemIds,
                listState = rememberLazyListState(),
                rowHeight = rowHeight,
            )
        }

        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread {
            itemIds = listOf("C", "D", "A")
        }
        composeRule.runOnIdle { }

        val rowHeightPx = composeRule.density.run { rowHeight.toPx() }
        var sawOverlap = false
        for (frame in 0 until 30) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { }
            val aTop = rowTop("A")
            val cTop = rowTop("C")
            val overlapTop = maxOf(aTop, cTop)
            val overlapBottom = minOf(aTop + rowHeightPx, cTop + rowHeightPx)
            if (overlapBottom - overlapTop > 4f) {
                sawOverlap = true
                break
            }
        }
        assertTrue("shared rows never overlapped during placement", sawOverlap)
        assertTrue(
            "target head C must rank above lower rows while their paths cross",
            chatListTargetZIndex(itemIds.indexOf("C")) > chatListTargetZIndex(itemIds.indexOf("A")),
        )
    }

    @Test
    fun contentOnlyUpdateWithUnchangedHeadDoesNotMoveRows() {
        var itemIds by mutableStateOf(listOf("A", "B"))
        var contentRevision by mutableIntStateOf(0)

        composeRule.setContent {
            val listState = rememberLazyListState()
            ChatListHeadReorderMotionHarness(
                itemIds = itemIds,
                listState = listState,
                rowHeight = rowHeight,
                contentRevision = contentRevision,
            )
        }

        composeRule.waitForIdle()
        val aBefore = rowTop("A")
        val bBefore = rowTop("B")

        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread {
            contentRevision = 1
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()

        assertEquals(aBefore, rowTop("A"), 0.5f)
        assertEquals(bBefore, rowTop("B"), 0.5f)
    }

    @Test
    fun userInterruptionDoesNotCancelFutureHeadReorderCorrections() {
        var itemIds by mutableStateOf(listOf("A", "B", "C"))
        val listStateHolder = arrayOf<LazyListState?>(null)

        composeRule.setContent {
            val listState = rememberLazyListState()
            listStateHolder[0] = listState
            ChatListHeadReorderMotionHarness(
                itemIds = itemIds,
                listState = listState,
                rowHeight = rowHeight,
            )
        }

        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread {
            itemIds = listOf("B", "A", "C")
        }
        composeRule.runOnIdle { }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle {
            assertTrue(
                "first head correction should be active before interruption",
                listStateHolder[0]!!.isScrollInProgress,
            )
        }

        composeRule.onNodeWithTag(CHAT_LIST_HEAD_REORDER_LIST_TAG).performTouchInput {
            down(center)
            moveBy(Offset(x = 0f, y = 100f))
            up()
        }
        composeRule.runOnIdle {
            assertEquals(0, listStateHolder[0]!!.firstVisibleItemIndex)
            assertEquals(0, listStateHolder[0]!!.firstVisibleItemScrollOffset)
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.runOnIdle { }
        assertEquals(0f, rowTop("B"), 0.5f)

        composeRule.runOnUiThread {
            itemIds = listOf("C", "B", "A")
        }
        composeRule.runOnIdle { }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle {
            assertTrue(
                "a later head correction should still animate after user interruption",
                listStateHolder[0]!!.isScrollInProgress,
            )
        }

        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.runOnIdle { }
        assertEquals(0f, rowTop("C"), 0.5f)
    }

    @Test
    fun rapidHeadReordersNeverDispatchATapToACrossingRow() {
        var itemIds by mutableStateOf(listOf("A", "B"))
        val openedIds = mutableListOf<String>()

        composeRule.setContent {
            ChatListHeadReorderMotionHarness(
                itemIds = itemIds,
                listState = rememberLazyListState(),
                rowHeight = rowHeight,
                onOpen = openedIds::add,
            )
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false

        repeat(100) { iteration ->
            val targetHead = if (iteration % 2 == 0) "B" else "A"
            composeRule.runOnUiThread {
                itemIds = if (targetHead == "A") listOf("A", "B") else listOf("B", "A")
            }
            composeRule.runOnIdle { }
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { }

            // The target head is visibly crossing the previous row. A tap in
            // this window may be ignored, but must never open either stale
            // layout target.
            composeRule.onNodeWithTag(chatListHeadReorderRowTag(targetHead)).performTouchInput {
                down(center)
                up()
            }
            composeRule.runOnIdle {
                assertEquals("a crossing-row tap opened a chat at iteration $iteration", iteration, openedIds.size)
            }

            composeRule.mainClock.advanceTimeBy(CHAT_LIST_HEAD_INPUT_GATE_MILLIS + 500L)
            composeRule.runOnIdle { }
            composeRule.onNodeWithTag(chatListHeadReorderRowTag(targetHead)).performTouchInput {
                down(center)
                up()
            }
            composeRule.runOnIdle {
                assertEquals(targetHead, openedIds.last())
                assertEquals(iteration + 1, openedIds.size)
            }
        }
    }

    @Test
    fun reorderedRowsAreNeverFirstComposedWithInteractionsEnabled() {
        var itemIds by mutableStateOf(listOf("A", "B"))
        val publishedCompositions = mutableListOf<Pair<List<String>, Boolean>>()

        composeRule.setContent {
            ChatListHeadReorderMotionHarness(
                itemIds = itemIds,
                listState = rememberLazyListState(),
                rowHeight = rowHeight,
                onRowsComposed = { ids, interactionsEnabled ->
                    publishedCompositions += ids to interactionsEnabled
                },
            )
        }
        composeRule.waitForIdle()
        publishedCompositions.clear()

        composeRule.runOnUiThread { itemIds = listOf("B", "A") }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val firstReorderedComposition =
                publishedCompositions.first { (ids, _) -> ids == listOf("B", "A") }
            assertFalse(
                "the first composition publishing reordered rows exposed input before effects ran",
                firstReorderedComposition.second,
            )
        }

        // Return to the original head before the first gate settles. Keying by
        // the newly composed head must create another closed gate rather than
        // mistaking the original id for an already-settled state.
        publishedCompositions.clear()
        composeRule.runOnUiThread { itemIds = listOf("A", "B") }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val firstReturningComposition =
                publishedCompositions.first { (ids, _) -> ids == listOf("A", "B") }
            assertFalse(firstReturningComposition.second)
        }

        composeRule.mainClock.advanceTimeBy(CHAT_LIST_HEAD_INPUT_GATE_MILLIS + 500L)
        composeRule.runOnIdle {
            assertTrue(publishedCompositions.last().second)
        }
    }

    private fun rowTop(id: String): Float =
        composeRule
            .onNodeWithTag(chatListHeadReorderRowTag(id))
            .fetchSemanticsNode()
            .boundsInRoot
            .top
}

private fun headDemotionTargetIndex(
    itemIds: List<String>,
    pinnedCount: Int?,
    demotion: ChatListHeadDemotion?,
    applied: Boolean,
): Int? =
    demotion
        ?.takeIf { applied }
        ?.viewportAnchor
        ?.let { itemIds.indexOf(it.chatId) }
        ?.let { rowIndex ->
            chatListHeadDemotionTargetIndex(
                rowIndex = rowIndex,
                pinnedBoundaryIndex = pinnedCount,
                leadingItemCount = 0,
            )
        }

@Composable
internal fun ChatListHeadReorderMotionHarness(
    itemIds: List<String>,
    listState: LazyListState,
    rowHeight: Dp,
    datasetKey: ChatListDatasetKey = ChatListDatasetKey(false, null, ""),
    contentRevision: Int = 0,
    pinnedCount: Int? = null,
    userHeadDemotion: ChatListHeadDemotion? = null,
    userHeadDemotionSettled: Boolean = false,
    onUserHeadDemotionConsumed: (ChatListHeadDemotion) -> Unit = {},
    onHeadScrollCorrectionStarted: () -> Unit = {},
    onOpen: (String) -> Unit = {},
    onRowsComposed: (List<String>, Boolean) -> Unit = { _, _ -> },
) {
    var scrollCorrectionInProgress by remember { mutableStateOf(false) }
    val viewportGeneration = rememberChatListUserGestureGeneration(listState)
    val pinnedOrder = pinnedCount?.let(itemIds::take).orEmpty()
    val userHeadDemotionTargetIndex =
        headDemotionTargetIndex(itemIds, pinnedCount, userHeadDemotion, userHeadDemotionSettled)
    ChatListActiveHeadScrollEffect(
        listState = listState,
        activeHeadId = itemIds.firstOrNull(),
        pinnedOrder = pinnedOrder,
        datasetKey = datasetKey,
        isActiveList = true,
        userHeadDemotion = userHeadDemotion,
        userHeadDemotionSettled = userHeadDemotionSettled,
        userHeadDemotionTargetIndex = userHeadDemotionTargetIndex,
        viewportGeneration = viewportGeneration,
        onUserHeadDemotionConsumed = onUserHeadDemotionConsumed,
        onHeadReorderInProgressChange = {
            if (it && !scrollCorrectionInProgress) onHeadScrollCorrectionStarted()
            scrollCorrectionInProgress = it
        },
    )
    val headReorderInProgress =
        rememberChatListHeadReorderGate(
            activeHeadId = itemIds.firstOrNull(),
            datasetKey = datasetKey,
            isActiveList = true,
            scrollCorrectionInProgress = scrollCorrectionInProgress,
        )
    val interactionsEnabled = !headReorderInProgress
    SideEffect {
        onRowsComposed(itemIds, interactionsEnabled)
    }
    LazyColumn(
        modifier = Modifier.testTag(CHAT_LIST_HEAD_REORDER_LIST_TAG),
        state = listState,
    ) {
        itemIds.forEachIndexed { targetIndex, id ->
            if (targetIndex == pinnedCount) {
                item(key = CHAT_LIST_PINNED_BOUNDARY_KEY) {
                    ChatListPinnedBoundary()
                }
            }
            item(key = id) {
                Box(modifier = chatListRowMotion(targetIndex)) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(rowHeight)
                                .clickable(enabled = interactionsEnabled) { onOpen(id) }
                                .testTag(chatListHeadReorderRowTag(id)),
                    ) {
                        Text("$id-$contentRevision")
                    }
                }
            }
        }
    }
}

internal fun chatListHeadReorderRowTag(id: String): String = "chat-list-head-row-$id"

internal const val CHAT_LIST_HEAD_REORDER_LIST_TAG = "chat-list-head"
