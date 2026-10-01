package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.ReadingScrollPosition
import dev.ipf.whitenoise.android.ui.common.readerScrollPosition
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationScrollIndicatorTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var state: LazyListState
    private lateinit var viewport: ConversationTimelineViewport
    private lateinit var scope: CoroutineScope
    private var firstMessageIndex = 0
    private val keys = mutableStateOf(listOf<Any>())

    @Test fun oneTallNativeRowMovesContinuouslyFromNewestToOldest() {
        render(listOf(600))
        val newest = position()!!
        assertEquals(1f, newest.progress, 0.001f)
        go(0, 150)
        val middle = position()!!
        assertTrue(middle.progress in 0f..<newest.progress)
        go(0, 600)
        assertEquals(0f, position()!!.progress, 0.001f)
        // The native tail padding reduces the measured visible extent at the newest edge.
        assertTrue(middle.visibleFraction >= newest.visibleFraction)
        assertEquals(newest.visibleFraction, middle.visibleFraction, 0.03f)
    }

    @Test fun mixedHeightsMoveWithinTheSameMessageWithoutChangingItsIndex() {
        render(listOf(80, 600, 100, 360, 60))
        go(1, 80)
        val before = position()!!
        assertEquals(1, state.firstVisibleItemIndex)
        go(1, 200)
        val after = position()!!
        assertEquals(1, state.firstVisibleItemIndex)
        assertTrue(after.progress < before.progress)
        assertTrue(after.visibleFraction > 0f && after.visibleFraction < 1f)
    }

    @Test fun fittingMessagesDoNotShowAnIndicator() {
        render(listOf(40, 40, 40))
        assertNull(position())
        assertTrue(!state.canScrollForward && !state.canScrollBackward)
    }

    @Test fun structuralErrorRowsAreExcludedAndStaleWindowKeysAreRejected() {
        render(listOf(600), bottomError = true)
        assertNotNull(position())
        go(1, 120)
        val layout = viewport.readingLayoutInfo()
        assertNotNull(conversationScrollPosition(layout, keys.value, 1, keys.value.size + 2))
        assertNull(conversationScrollPosition(layout, listOf("replacement"), 1, keys.value.size + 2))
        assertNull(conversationScrollPosition(layout, keys.value, 0, keys.value.size + firstMessageIndex + 1))
        composeRule.runOnIdle { keys.value = listOf("replacement") }
        composeRule.waitForIdle()
        assertNotNull(position())
    }

    @Test fun invalidNativeMeasurementsHideInsteadOfPaintingOldGeometry() {
        render(listOf(600))
        val layout = viewport.readingLayoutInfo()
        assertNull(
            conversationScrollPosition(
                object : LazyListLayoutInfo by layout {
                    override val viewportSize = IntSize(360, 0)
                },
                keys.value,
                0,
                keys.value.size + firstMessageIndex + 1,
            ),
        )
        assertNull(
            conversationScrollPosition(
                object : LazyListLayoutInfo by layout {
                    override val visibleItemsInfo =
                        layout.visibleItemsInfo.map { row ->
                            object : LazyListItemInfo by row {
                                override val size = 0
                            }
                        }
                },
                keys.value,
                0,
                keys.value.size + firstMessageIndex + 1,
            ),
        )
        assertNull(conversationScrollPosition(layout, keys.value, -1, keys.value.size + 1))
        assertNull(
            conversationScrollPosition(
                object : LazyListLayoutInfo by layout {
                    override val totalItemsCount = layout.totalItemsCount + 1
                },
                keys.value,
                0,
                keys.value.size + 1,
            ),
        )
    }

    @Test fun readerUsesItsActualRangeAndRejectsUnmeasuredOrStaleRanges() {
        assertEquals(ReadingScrollPosition(0f, 0.25f), readerScrollPosition(0, 300, 100))
        assertEquals(ReadingScrollPosition(0.5f, 0.25f), readerScrollPosition(150, 300, 100))
        assertEquals(ReadingScrollPosition(1f, 0.25f), readerScrollPosition(300, 300, 100))
        assertNull(readerScrollPosition(0, 0, 100))
        assertNull(readerScrollPosition(0, Int.MAX_VALUE, 100))
        assertNull(readerScrollPosition(0, 100, 0))
        assertNull(readerScrollPosition(101, 100, 100))
        assertNull(readerScrollPosition(-1, 100, 100))
    }

    private fun render(
        heights: List<Int>,
        bottomError: Boolean = false,
    ) {
        firstMessageIndex = if (bottomError) 1 else 0
        keys.value = heights.indices.map { "message-$it" }
        composeRule.setContent {
            state = rememberLazyListState()
            viewport = remember(state) { ConversationTimelineViewport(state) }
            scope = rememberCoroutineScope()
            WhiteNoiseTheme {
                Box(
                    Modifier.fillMaxWidth().height(240.dp).conversationScrollIndicator(
                        state,
                        viewport,
                        ConversationScrollIndicatorWindow(
                            keys.value,
                            firstMessageIndex,
                            keys.value.size + firstMessageIndex + 1,
                            "fixture",
                        ),
                        true,
                    ),
                ) {
                    LazyColumn(
                        state = state,
                        reverseLayout = true,
                        verticalArrangement = CONVERSATION_TIMELINE_VERTICAL_ARRANGEMENT,
                        contentPadding = conversationTimelineContentPadding(0.dp, 0.dp),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                                .measureConversationTimelinePadding(viewport, CONVERSATION_TIMELINE_TAIL_GAP, 0.dp)
                                .onGloballyPositioned(viewport::onPaintViewportMeasured),
                    ) {
                        if (bottomError) item(key = "conversation-load-error-bottom") { Box(Modifier.height(40.dp)) }
                        itemsIndexed(keys.value, key = { _, key -> key }) { index, key ->
                            Box(Modifier.fillMaxWidth().height(heights[index].dp)) { Text(key.toString()) }
                        }
                        item(key = "top-spacer") { Box(Modifier.height(4.dp)) }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun go(
        index: Int,
        offset: Int,
    ) {
        composeRule.runOnIdle { scope.launch { state.scrollToItem(index, offset) } }
        composeRule.waitForIdle()
    }

    private fun position(): ReadingScrollPosition? =
        composeRule.runOnIdle {
            conversationScrollPosition(
                viewport.readingLayoutInfo(),
                keys.value,
                firstMessageIndex,
                keys.value.size + firstMessageIndex + 1,
            )
        }
}
