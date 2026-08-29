package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
class ChatListHeadUnpinAnimationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Suppress("LongMethod")
    fun visibleHeadUnpinAnimatesIntoItsUnpinnedSlotWithoutMovingTheViewport() {
        val rowHeight = 48.dp
        val tail = (0 until 20).map { "E$it" }
        var itemIds by mutableStateOf(listOf("A", "B", "C") + tail)
        var pinnedCount by mutableIntStateOf(2)
        var demotion by mutableStateOf<ChatListHeadDemotion?>(null)
        var demotionApplied by mutableStateOf(false)
        val listStateHolder = arrayOf<LazyListState?>(null)
        val interactionStates = mutableListOf<Boolean>()

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
                onRowsComposed = { _, interactionsEnabled -> interactionStates += interactionsEnabled },
            )
        }

        composeRule.waitForIdle()
        val listState = listStateHolder[0]!!
        val startATop = rowTop("A")
        val startBTop = rowTop("B")
        assertEquals(0, listState.firstVisibleItemIndex)
        assertEquals(0, listState.firstVisibleItemScrollOffset)
        interactionStates.clear()

        composeRule.mainClock.autoAdvance = false
        val transaction =
            ChatListHeadDemotion(
                chatId = "A",
                transactionId = 1L,
                viewportAnchor = listState.chatListViewportAnchor(itemIds.toSet()),
            )
        composeRule.runOnUiThread {
            demotion = transaction
            itemIds = listOf("B", "C", "A") + tail
            pinnedCount = 1
            demotionApplied = true
        }
        composeRule.runOnIdle { }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle {
            assertTrue("the first unpin composition must gate row input", interactionStates.last().not())
        }

        val firstATop = rowTop("A")
        val firstBTop = rowTop("B")
        assertTrue(firstATop + 0.5f >= startATop)
        assertTrue(firstBTop <= startBTop + 0.5f)
        val sampledATops = mutableListOf(firstATop)
        var previousATop = firstATop
        var previousBTop = firstBTop
        repeat(29) { frame ->
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { }
            val aTop = rowTop("A")
            val bTop = rowTop("B")
            sampledATops += aTop
            assertTrue(
                "demoted row reversed at frame $frame: $previousATop -> $aTop",
                aTop + 0.5f >= previousATop,
            )
            assertTrue(
                "new pinned head reversed at frame $frame: $previousBTop -> $bTop",
                bTop <= previousBTop + 0.5f,
            )
            previousATop = aTop
            previousBTop = bTop
        }

        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.runOnIdle { }
        val settledATop = rowTop("A")
        assertTrue("the unpinned row must finish below its pinned position", settledATop > startATop + 1f)
        assertTrue(
            "the unpinned row snapped without an intermediate placement frame",
            sampledATops.any { it > startATop + 1f && it < settledATop - 1f },
        )
        assertEquals(0f, rowTop("B"), 0.5f)
        assertEquals(0, listState.firstVisibleItemIndex)
        assertEquals(0, listState.firstVisibleItemScrollOffset)
        assertFalse(listState.isScrollInProgress)
        assertTrue("row input must reopen after placement settles", interactionStates.last())
    }

    private fun rowTop(id: String): Float =
        composeRule
            .onNodeWithTag(chatListHeadReorderRowTag(id))
            .fetchSemanticsNode()
            .boundsInRoot
            .top
}
