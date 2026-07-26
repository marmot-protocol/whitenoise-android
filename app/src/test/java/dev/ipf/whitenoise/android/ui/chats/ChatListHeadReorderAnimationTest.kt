package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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

    private fun rowTop(id: String): Float =
        composeRule
            .onNodeWithTag(chatListHeadReorderRowTag(id))
            .fetchSemanticsNode()
            .boundsInRoot
            .top
}

@Composable
private fun ChatListHeadReorderMotionHarness(
    itemIds: List<String>,
    listState: LazyListState,
    rowHeight: Dp,
    contentRevision: Int = 0,
) {
    ChatListActiveHeadScrollEffect(
        listState = listState,
        activeHeadId = itemIds.firstOrNull(),
        isActiveList = true,
    )
    LazyColumn(state = listState) {
        items(itemIds, key = { it }) { id ->
            Box(modifier = chatListHeadReorderPlacement()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(rowHeight)
                            .testTag(chatListHeadReorderRowTag(id)),
                ) {
                    Text("$id-$contentRevision")
                }
            }
        }
    }
}

private fun chatListHeadReorderRowTag(id: String): String = "chat-list-head-row-$id"
