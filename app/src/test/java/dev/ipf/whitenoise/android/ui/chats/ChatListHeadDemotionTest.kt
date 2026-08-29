package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatListHeadDemotionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val rowHeight = 48.dp

    @Test
    fun autoScrollViewportGenerationAdvancesOnlyWhenSessionStarts() {
        val scrollDeltas = listOf(18f, 18f, 18f, 0f, -18f, -18f, 0f)
        var autoScrollActive = false
        var generationAdvances = 0

        scrollDeltas.forEach { scrollDelta ->
            if (chatListAutoScrollSessionStarts(autoScrollActive, scrollDelta)) {
                generationAdvances += 1
            }
            autoScrollActive = scrollDelta != 0f
        }

        assertEquals(2, generationAdvances)
    }

    @Test
    fun headDemotionTargetIndexIncludesLeadingSyntheticItemsAndBoundary() {
        assertEquals(
            1,
            chatListHeadDemotionTargetIndex(
                rowIndex = 0,
                pinnedBoundaryIndex = 1,
                leadingItemCount = 1,
            ),
        )
        assertEquals(
            3,
            chatListHeadDemotionTargetIndex(
                rowIndex = 1,
                pinnedBoundaryIndex = 1,
                leadingItemCount = 1,
            ),
        )
        assertEquals(
            3,
            chatListHeadDemotionTargetIndex(
                rowIndex = 2,
                pinnedBoundaryIndex = null,
                leadingItemCount = 1,
            ),
        )
        assertEquals(
            null,
            chatListHeadDemotionTargetIndex(
                rowIndex = -1,
                pinnedBoundaryIndex = 1,
                leadingItemCount = 1,
            ),
        )
    }

    @Test
    @Suppress("LongMethod")
    fun unpinVisibleHeadWithLeadingItemPreservesViewportWithoutHeadScrollCorrection() {
        val tail = (0 until 20).map { "E$it" }
        var itemIds by mutableStateOf(listOf("A", "B", "C") + tail)
        var pinnedCount by mutableIntStateOf(2)
        var demotion by mutableStateOf<ChatListHeadDemotion?>(null)
        var demotionApplied by mutableStateOf(false)
        val listStateHolder = arrayOf<LazyListState?>(null)
        val correctionStarts = mutableListOf<Unit>()
        val consumed = mutableListOf<ChatListHeadDemotion>()
        val interactionStates = mutableListOf<Boolean>()

        composeRule.setContent {
            val listState = rememberLazyListState()
            listStateHolder[0] = listState
            ChatListHeadReorderMotionHarness(
                itemIds = itemIds,
                listState = listState,
                rowHeight = rowHeight,
                pinnedCount = pinnedCount,
                leadingItemCount = 1,
                userHeadDemotion = demotion,
                userHeadDemotionSettled = demotionApplied,
                onUserHeadDemotionConsumed = {
                    consumed += it
                    if (demotion == it) demotion = null
                },
                onHeadScrollCorrectionStarted = { correctionStarts += Unit },
                onRowsComposed = { _, interactionsEnabled -> interactionStates += interactionsEnabled },
            )
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle { listStateHolder[0]!!.requestScrollToItem(1, 12) }
        composeRule.waitForIdle()
        val anchoredTop = rowTop("A")
        assertEquals(0f, anchoredTop, 0.5f)

        composeRule.mainClock.autoAdvance = false
        val transaction = listStateHolder[0]!!.headDemotion("A", 1L, itemIds)
        composeRule.runOnUiThread { demotion = transaction }
        composeRule.runOnIdle { }
        composeRule.runOnUiThread {
            itemIds = listOf("B", "C", "A") + tail
            pinnedCount = 1
            demotionApplied = true
        }
        composeRule.runOnIdle { }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle {
            val listState = listStateHolder[0]!!
            assertFalse("a user-requested unpin must not launch head scrolling", listState.isScrollInProgress)
            assertEquals(1, listState.firstVisibleItemIndex)
            assertEquals(12, listState.firstVisibleItemScrollOffset)
            assertEquals(
                "B",
                listState.layoutInfo.visibleItemsInfo
                    .first { it.key is String && it.key in itemIds }
                    .key,
            )
            val rowKeys =
                listState.layoutInfo.visibleItemsInfo
                    .map { it.key }
                    .filterIsInstance<String>()
            assertEquals(rowKeys.size, rowKeys.toSet().size)
        }
        assertTrue(rowTop("A") >= anchoredTop)
        assertTrue("the first demotion composition must gate row input", interactionStates.last().not())

        composeRule.mainClock.advanceTimeBy(CHAT_LIST_HEAD_INPUT_GATE_MILLIS + 500L)
        composeRule.runOnIdle { }
        assertEquals(listOf(transaction), consumed)
        assertTrue(correctionStarts.isEmpty())
        assertTrue("row input must reopen after the one placement transaction", interactionStates.last())
        assertTrue("the unpinned row must settle below its former head slot", rowTop("A") > anchoredTop + 1f)
    }

    @Test
    fun authoritativeUnpinRedeliveryDoesNotReplayPlacementMotion() {
        val tail = (0 until 20).map { "E$it" }
        var itemIds by mutableStateOf(listOf("A", "B", "C") + tail)
        var pinnedCount by mutableIntStateOf(2)
        var contentRevision by mutableIntStateOf(0)
        var demotion by mutableStateOf<ChatListHeadDemotion?>(null)
        var demotionApplied by mutableStateOf(false)
        var consumedCount = 0
        var correctionStarts = 0
        val listStateHolder = arrayOf<LazyListState?>(null)

        composeRule.setContent {
            val listState = rememberLazyListState()
            listStateHolder[0] = listState
            ChatListHeadReorderMotionHarness(
                itemIds = itemIds,
                listState = listState,
                rowHeight = rowHeight,
                pinnedCount = pinnedCount,
                contentRevision = contentRevision,
                userHeadDemotion = demotion,
                userHeadDemotionSettled = demotionApplied,
                onUserHeadDemotionConsumed = {
                    consumedCount += 1
                    if (demotion == it) demotion = null
                },
                onHeadScrollCorrectionStarted = { correctionStarts += 1 },
            )
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle { listStateHolder[0]!!.requestScrollToItem(0, 9) }
        composeRule.waitForIdle()
        val anchoredTop = rowTop("A")
        val transaction = listStateHolder[0]!!.headDemotion("A", 1L, itemIds)
        composeRule.runOnUiThread { demotion = transaction }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            itemIds = listOf("B", "C", "A") + tail
            pinnedCount = 1
            demotionApplied = true
        }
        composeRule.waitForIdle()

        assertEquals(1, consumedCount)
        assertEquals(0, correctionStarts)
        val settledATop = rowTop("A")
        assertTrue(settledATop > anchoredTop + 1f)
        assertEquals(0, listStateHolder[0]!!.firstVisibleItemIndex)
        assertEquals(9, listStateHolder[0]!!.firstVisibleItemScrollOffset)

        // Matching stream redelivery republishes content but not a second
        // normalized order or a second demotion transaction.
        composeRule.runOnUiThread { contentRevision += 1 }
        composeRule.waitForIdle()

        assertEquals(1, consumedCount)
        assertEquals(0, correctionStarts)
        assertFalse(listStateHolder[0]!!.isScrollInProgress)
        assertEquals(settledATop, rowTop("A"), 0.5f)
    }

    @Test
    @Suppress("LongMethod")
    fun unpinWhileUserScrollsDoesNotOverrideGesture() {
        val tail = (0 until 30).map { "E$it" }
        var itemIds by mutableStateOf(listOf("A", "B", "C") + tail)
        var pinnedCount by mutableIntStateOf(2)
        var demotion by mutableStateOf<ChatListHeadDemotion?>(null)
        var demotionApplied by mutableStateOf(false)
        var correctionStarts = 0
        val listStateHolder = arrayOf<LazyListState?>(null)
        composeRule.setContent {
            val listState = rememberLazyListState()
            listStateHolder[0] = listState
            ChatListHeadReorderMotionHarness(
                itemIds = itemIds,
                listState = listState,
                rowHeight = rowHeight,
                pinnedCount = pinnedCount,
                userHeadDemotion = demotion,
                userHeadDemotionSettled = demotionApplied,
                onUserHeadDemotionConsumed = {
                    if (demotion == it) demotion = null
                },
                onHeadScrollCorrectionStarted = { correctionStarts += 1 },
            )
        }

        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            demotion = listStateHolder[0]!!.headDemotion("A", 1L, itemIds)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CHAT_LIST_HEAD_REORDER_LIST_TAG).performTouchInput {
            down(center)
            moveBy(Offset(x = 0f, y = -72f))
            up()
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertFalse(listStateHolder[0]!!.isScrollInProgress) }

        val gestureAnchorKey =
            listStateHolder[0]!!
                .layoutInfo.visibleItemsInfo
                .first()
                .key as String
        val gestureAnchorOffset = listStateHolder[0]!!.firstVisibleItemScrollOffset
        val gestureAnchorTop = rowTop(gestureAnchorKey)
        composeRule.runOnUiThread {
            itemIds = listOf("B", "C", "A") + tail
            pinnedCount = 1
            demotionApplied = true
        }
        composeRule.waitForIdle()

        assertEquals(0, correctionStarts)
        assertFalse(listStateHolder[0]!!.isScrollInProgress)
        assertEquals(
            gestureAnchorKey,
            listStateHolder[0]!!
                .layoutInfo.visibleItemsInfo
                .first()
                .key,
        )
        assertEquals(gestureAnchorOffset, listStateHolder[0]!!.firstVisibleItemScrollOffset)
        assertEquals(gestureAnchorTop, rowTop(gestureAnchorKey), 0.5f)
    }

    @Test
    fun unpinLastPinnedHeadPreservesDomainAnchorWhenBoundaryDisappears() {
        val tail = (0 until 30).map { "E$it" }
        var itemIds by mutableStateOf(listOf("A", "B", "C") + tail)
        var pinnedCount by mutableStateOf<Int?>(1)
        var demotion by mutableStateOf<ChatListHeadDemotion?>(null)
        var demotionApplied by mutableStateOf(false)
        val listStateHolder = arrayOf<LazyListState?>(null)

        composeRule.setContent {
            val listState = rememberLazyListState()
            listStateHolder[0] = listState
            ChatListHeadReorderMotionHarness(
                itemIds = itemIds,
                listState = listState,
                rowHeight = rowHeight,
                pinnedCount = pinnedCount,
                userHeadDemotion = demotion,
                userHeadDemotionSettled = demotionApplied,
                onUserHeadDemotionConsumed = {
                    if (demotion == it) demotion = null
                },
            )
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle { listStateHolder[0]!!.requestScrollToItem(1, 3) }
        composeRule.waitForIdle()
        val anchorTop = rowTop("B")
        assertEquals(
            CHAT_LIST_PINNED_BOUNDARY_KEY,
            listStateHolder[0]!!
                .layoutInfo.visibleItemsInfo
                .first()
                .key,
        )

        composeRule.runOnUiThread {
            demotion = listStateHolder[0]!!.headDemotion("A", 1L, itemIds)
            itemIds = listOf("B", "C", "A") + tail
            pinnedCount = null
            demotionApplied = true
        }
        composeRule.waitForIdle()

        assertEquals(
            "B",
            listStateHolder[0]!!
                .layoutInfo.visibleItemsInfo
                .first()
                .key,
        )
        assertEquals(
            "the nearest domain row remains the anchor when the leading synthetic item disappears",
            0f,
            rowTop("B"),
            0.5f,
        )
        assertTrue(anchorTop > rowTop("B"))
    }

    @Test
    fun unpinOffscreenHeadPreservesCurrentViewportAnchor() {
        val tail = (0 until 30).map { "E$it" }
        var itemIds by mutableStateOf(listOf("A", "B", "C") + tail)
        var pinnedCount by mutableIntStateOf(2)
        var demotion by mutableStateOf<ChatListHeadDemotion?>(null)
        var demotionApplied by mutableStateOf(false)
        var correctionStarts = 0
        val listStateHolder = arrayOf<LazyListState?>(null)

        composeRule.setContent {
            val listState = rememberLazyListState()
            listStateHolder[0] = listState
            ChatListHeadReorderMotionHarness(
                itemIds = itemIds,
                listState = listState,
                rowHeight = rowHeight,
                pinnedCount = pinnedCount,
                userHeadDemotion = demotion,
                userHeadDemotionSettled = demotionApplied,
                onUserHeadDemotionConsumed = {
                    if (demotion == it) demotion = null
                },
                onHeadScrollCorrectionStarted = { correctionStarts += 1 },
            )
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle { listStateHolder[0]!!.requestScrollToItem(8, 13) }
        composeRule.waitForIdle()
        val viewportAnchorKey =
            listStateHolder[0]!!
                .layoutInfo.visibleItemsInfo
                .first()
                .key as String
        val viewportAnchorOffset = listStateHolder[0]!!.firstVisibleItemScrollOffset
        val viewportAnchorTop = rowTop(viewportAnchorKey)

        composeRule.runOnUiThread {
            demotion = listStateHolder[0]!!.headDemotion("A", 1L, itemIds)
            itemIds = listOf("B", "C", "A") + tail
            pinnedCount = 1
            demotionApplied = true
        }
        composeRule.waitForIdle()

        assertEquals(0, correctionStarts)
        assertEquals(
            viewportAnchorKey,
            listStateHolder[0]!!
                .layoutInfo.visibleItemsInfo
                .first()
                .key,
        )
        assertEquals(viewportAnchorOffset, listStateHolder[0]!!.firstVisibleItemScrollOffset)
        assertEquals(viewportAnchorTop, rowTop(viewportAnchorKey), 0.5f)
    }

    @Test
    fun failedUnpinRollbackDoesNotLaunchContradictoryCorrection() {
        val tail = (0 until 20).map { "E$it" }
        var itemIds by mutableStateOf(listOf("A", "B", "C") + tail)
        var pinnedCount by mutableIntStateOf(2)
        var demotion by mutableStateOf<ChatListHeadDemotion?>(null)
        var demotionApplied by mutableStateOf(false)
        var correctionStarts = 0
        val listStateHolder = arrayOf<LazyListState?>(null)

        composeRule.setContent {
            val listState = rememberLazyListState()
            listStateHolder[0] = listState
            ChatListHeadReorderMotionHarness(
                itemIds = itemIds,
                listState = listState,
                rowHeight = rowHeight,
                pinnedCount = pinnedCount,
                userHeadDemotion = demotion,
                userHeadDemotionSettled = demotionApplied,
                onUserHeadDemotionConsumed = {
                    if (demotion == it) demotion = null
                },
                onHeadScrollCorrectionStarted = { correctionStarts += 1 },
            )
        }

        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            demotion = listStateHolder[0]!!.headDemotion("A", 1L, itemIds)
            itemIds = listOf("B", "C", "A") + tail
            pinnedCount = 1
            demotionApplied = true
        }
        composeRule.waitForIdle()
        assertEquals(
            "B",
            listStateHolder[0]!!
                .layoutInfo.visibleItemsInfo
                .first { it.key is String && it.key in itemIds }
                .key,
        )

        // A failed engine mutation rolls the optimistic pin state back. The
        // rollback restores the original item order while retaining the
        // current viewport, without starting a competing head correction.
        composeRule.runOnUiThread {
            demotionApplied = false
            itemIds = listOf("A", "B", "C") + tail
            pinnedCount = 2
        }
        composeRule.waitForIdle()

        assertEquals(0, correctionStarts)
        assertFalse(listStateHolder[0]!!.isScrollInProgress)
        assertEquals(
            "B",
            listStateHolder[0]!!
                .layoutInfo.visibleItemsInfo
                .first { it.key is String && it.key in itemIds }
                .key,
        )
        assertEquals(0f, rowTop("B"), 0.5f)
    }

    @Test
    @Suppress("LongMethod")
    fun rapidPinUnpinSettlesOnceAtAuthoritativeOrder() {
        val tail = (0 until 20).map { "E$it" }
        var itemIds by mutableStateOf(listOf("A", "B", "C") + tail)
        var pinnedCount by mutableIntStateOf(2)
        var contentRevision by mutableIntStateOf(0)
        var demotion by mutableStateOf<ChatListHeadDemotion?>(null)
        var demotionApplied by mutableStateOf(false)
        val consumedTransactions = mutableListOf<Long>()
        var correctionStarts = 0
        val listStateHolder = arrayOf<LazyListState?>(null)

        composeRule.setContent {
            val listState = rememberLazyListState()
            listStateHolder[0] = listState
            ChatListHeadReorderMotionHarness(
                itemIds = itemIds,
                listState = listState,
                rowHeight = rowHeight,
                pinnedCount = pinnedCount,
                contentRevision = contentRevision,
                userHeadDemotion = demotion,
                userHeadDemotionSettled = demotionApplied,
                onUserHeadDemotionConsumed = {
                    consumedTransactions += it.transactionId
                    if (demotion == it) demotion = null
                },
                onHeadScrollCorrectionStarted = { correctionStarts += 1 },
            )
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle { listStateHolder[0]!!.requestScrollToItem(0, 7) }
        composeRule.waitForIdle()
        val anchoredTop = rowTop("A")
        composeRule.mainClock.autoAdvance = false

        composeRule.runOnUiThread {
            demotion = listStateHolder[0]!!.headDemotion("A", 1L, itemIds)
        }
        composeRule.runOnIdle { }
        composeRule.runOnUiThread {
            itemIds = listOf("B", "C", "A") + tail
            pinnedCount = 1
            demotionApplied = true
        }
        composeRule.runOnIdle { }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle { }

        // Pin A again before placement settles, then immediately unpin it.
        composeRule.runOnUiThread {
            demotionApplied = false
            itemIds = listOf("A", "B", "C") + tail
            pinnedCount = 2
        }
        composeRule.runOnIdle { }
        composeRule.mainClock.advanceTimeByFrame()
        lateinit var secondTransaction: ChatListHeadDemotion
        composeRule.runOnUiThread {
            secondTransaction = listStateHolder[0]!!.headDemotion("A", 2L, itemIds)
            demotion = secondTransaction
        }
        composeRule.runOnIdle { }
        composeRule.runOnUiThread {
            itemIds = listOf("B", "C", "A") + tail
            pinnedCount = 1
            demotionApplied = true
        }
        composeRule.runOnIdle { }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle { }

        // The authoritative copy carries the same final normalized order.
        composeRule.runOnUiThread { contentRevision += 1 }
        composeRule.mainClock.advanceTimeBy(CHAT_LIST_HEAD_INPUT_GATE_MILLIS + 1_000L)
        composeRule.runOnIdle { }

        assertEquals(listOf(1L, 2L), consumedTransactions)
        assertEquals(0, correctionStarts)
        assertEquals(
            secondTransaction.viewportAnchor?.firstVisibleItemIndex,
            listStateHolder[0]!!.firstVisibleItemIndex,
        )
        assertEquals(
            secondTransaction.viewportAnchor?.firstVisibleItemScrollOffset,
            listStateHolder[0]!!.firstVisibleItemScrollOffset,
        )
        assertTrue(rowTop("A") > anchoredTop + 1f)
        assertFalse(listStateHolder[0]!!.isScrollInProgress)
    }

    private fun rowTop(id: String): Float =
        composeRule
            .onNodeWithTag(chatListHeadReorderRowTag(id))
            .fetchSemanticsNode()
            .boundsInRoot
            .top

    private fun LazyListState.headDemotion(
        chatId: String,
        transactionId: Long,
        visibleIds: List<String>,
    ): ChatListHeadDemotion =
        ChatListHeadDemotion(
            chatId = chatId,
            transactionId = transactionId,
            viewportAnchor = chatListViewportAnchor(visibleIds.toSet()),
        )
}
