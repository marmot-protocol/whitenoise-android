package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationBottomChromeHeightTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun distinctPostInitializationHeightChangesRequestOneReanchor() {
        val observer = ConversationBottomChromeHeightObserver()

        assertFalse(observer.onMeasured(80))
        assertFalse(observer.onMeasured(80))
        assertTrue(observer.onMeasured(160))
        assertFalse(observer.onMeasured(160))
        assertTrue(observer.onMeasured(80))
    }

    @Test
    fun latestMeasuredHeightIsAvailableToForegroundGeometryCapture() {
        val observer = ConversationBottomChromeHeightObserver()

        assertFalse(observer.hasMeasurement)
        assertEquals(0, observer.currentHeightPx)
        observer.onMeasured(0)
        assertTrue(observer.hasMeasurement)
        assertEquals(0, observer.currentHeightPx)
        assertTrue(observer.onMeasured(96))
        assertEquals(96, observer.currentHeightPx)
    }

    @Test
    fun growingBottomChromeAndAppendingAtTailKeepsNewestRowFullyVisible() {
        val listState = LazyListState()
        val timelineSize = mutableStateOf(20)
        val bottomChromeHeight = mutableStateOf(60.dp)

        composeRule.setContent {
            BottomChromeHarness(
                listState = listState,
                timelineSize = timelineSize,
                bottomChromeHeight = bottomChromeHeight,
                initialMode = ConversationScrollMode.FollowingTail,
            )
        }
        composeRule.waitForIdle()
        scrollTo(listState, timelineSize.value - 1)

        composeRule.runOnUiThread {
            timelineSize.value = 21
            bottomChromeHeight.value = 160.dp
        }
        composeRule.waitForIdle()

        val tailBounds = composeRule.onNodeWithTag(TAIL_ROW_TAG).fetchSemanticsNode().boundsInRoot
        val bottomChromeBounds = composeRule.onNodeWithTag(BOTTOM_CHROME_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(tailBounds.bottom <= bottomChromeBounds.top)
    }

    @Test
    fun growingBottomChromeAndAppendingDoesNotPullAHistoryReaderToTail() {
        val listState = LazyListState()
        val timelineSize = mutableStateOf(30)
        val bottomChromeHeight = mutableStateOf(60.dp)

        composeRule.setContent {
            BottomChromeHarness(
                listState = listState,
                timelineSize = timelineSize,
                bottomChromeHeight = bottomChromeHeight,
                initialMode = ConversationScrollMode.ReadingHistory("message-8", 0),
            )
        }
        composeRule.waitForIdle()
        scrollTo(listState, 8)

        composeRule.runOnUiThread {
            timelineSize.value = 31
            bottomChromeHeight.value = 160.dp
        }
        composeRule.waitForIdle()

        assertEquals(8, listState.firstVisibleItemIndex)
    }

    @Test
    fun lastMessageReactionGrowthSettlesAtTailAfterDelayedMeasurement() {
        val followingListState = LazyListState()
        val historyListState = LazyListState()
        val reactionProjection = mutableIntStateOf(0)
        val followingWriter = CountingLazyListScrollWriter(followingListState)
        val historyWriter = CountingLazyListScrollWriter(historyListState)

        composeRule.setContent {
            Column {
                ReactionTailHarness(
                    listState = followingListState,
                    writer = followingWriter,
                    reactionProjection = reactionProjection,
                    initialMode = ConversationScrollMode.FollowingTail,
                    tagPrefix = "following",
                )
                ReactionTailHarness(
                    listState = historyListState,
                    writer = historyWriter,
                    reactionProjection = reactionProjection,
                    initialMode = ConversationScrollMode.ReadingHistory("message-4", 7),
                    tagPrefix = "history",
                )
            }
        }
        composeRule.waitForIdle()
        scrollTo(followingListState, REACTION_TAIL_ROW_COUNT - 1)
        scrollTo(historyListState, 4, 7)
        val historyIndex = historyListState.firstVisibleItemIndex
        val historyOffset = historyListState.firstVisibleItemScrollOffset

        composeRule.runOnUiThread { reactionProjection.intValue = 1 }
        composeRule.waitForIdle()

        val tailBounds =
            composeRule
                .onNodeWithTag("following-$REACTION_TAIL_ROW_TAG")
                .fetchSemanticsNode()
                .boundsInRoot
        val composerBounds =
            composeRule
                .onNodeWithTag("following-$REACTION_COMPOSER_TAG")
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(tailBounds.bottom <= composerBounds.top)
        assertEquals(composerBounds.top, tailBounds.bottom, 1f)
        assertTrue(followingWriter.snapCount > 1)
        assertEquals(historyIndex, historyListState.firstVisibleItemIndex)
        assertEquals(historyOffset, historyListState.firstVisibleItemScrollOffset)
        assertEquals(0, historyWriter.snapCount)

        val settledSnapCount = followingWriter.snapCount
        composeRule.runOnUiThread { reactionProjection.intValue = 1 }
        composeRule.waitForIdle()

        assertEquals(settledSnapCount, followingWriter.snapCount)
        assertEquals(historyIndex, historyListState.firstVisibleItemIndex)
        assertEquals(historyOffset, historyListState.firstVisibleItemScrollOffset)
    }

    @Composable
    private fun BottomChromeHarness(
        listState: LazyListState,
        timelineSize: MutableState<Int>,
        bottomChromeHeight: MutableState<Dp>,
        initialMode: ConversationScrollMode,
    ) {
        val scope = rememberCoroutineScope()
        val coordinator =
            remember(listState) {
                ConversationScrollCoordinator(
                    writer = LazyListConversationScrollWriter(listState),
                    initialMode = initialMode,
                )
            }
        val heightObserver = remember { ConversationBottomChromeHeightObserver() }

        BottomChromeScaffold(
            listState = listState,
            timelineSize = timelineSize.value,
            bottomChromeHeight = bottomChromeHeight.value,
            onHeightChanged = { heightPx ->
                if (heightObserver.onMeasured(heightPx)) {
                    scope.launch {
                        coordinator.followTailIfAllowed(
                            resolveTailIndex = { listState.layoutInfo.totalItemsCount - 1 },
                            reason = ConversationScrollReason.BottomInput,
                            frameCount = 1,
                        )
                    }
                }
            },
        )

        LaunchedEffect(timelineSize.value) {
            coordinator.followTailIfAllowed(
                resolveTailIndex = { listState.layoutInfo.totalItemsCount - 1 },
                reason = ConversationScrollReason.NewMessage,
            )
        }
    }

    @Composable
    private fun ReactionTailHarness(
        listState: LazyListState,
        writer: CountingLazyListScrollWriter,
        reactionProjection: MutableState<Int>,
        initialMode: ConversationScrollMode,
        tagPrefix: String,
    ) {
        val coordinator =
            remember(writer) {
                ConversationScrollCoordinator(
                    writer = writer,
                    initialMode = initialMode,
                )
            }
        var measuredReactionProjection by remember { mutableIntStateOf(0) }

        LaunchedEffect(reactionProjection.value) {
            if (reactionProjection.value > 0) {
                coordinator.settleTailAfterLayoutChange(
                    resolveTailIndex = { listState.layoutInfo.totalItemsCount - 1 },
                    captureLayout = { listState.captureReactionTailLayout() },
                )
            }
        }
        LaunchedEffect(reactionProjection.value) {
            if (reactionProjection.value > measuredReactionProjection) {
                withFrameNanos { }
                measuredReactionProjection = reactionProjection.value
            }
        }

        ReactionTailList(
            listState = listState,
            measuredReactionProjection = measuredReactionProjection,
            tagPrefix = tagPrefix,
        )
    }

    private fun LazyListState.captureReactionTailLayout(): ConversationTailLayout {
        val layoutInfo = layoutInfo
        val tailIndex = layoutInfo.totalItemsCount - 1
        val tailInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == tailIndex }
        return ConversationTailLayout(
            lastRowHeightPx = tailInfo?.size,
            tailOffsetPx = tailInfo?.offset,
            tailSizePx = tailInfo?.size,
            viewportEndOffsetPx = layoutInfo.viewportEndOffset,
        )
    }

    @Composable
    private fun ReactionTailList(
        listState: LazyListState,
        measuredReactionProjection: Int,
        tagPrefix: String,
    ) {
        Box(Modifier.size(width = 320.dp, height = 250.dp)) {
            Scaffold(
                bottomBar = {
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("$tagPrefix-$REACTION_COMPOSER_TAG"),
                    )
                },
            ) { padding ->
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {
                    items((0 until REACTION_TAIL_ROW_COUNT).toList()) { index ->
                        ReactionTailRow(index, measuredReactionProjection, tagPrefix)
                    }
                }
            }
        }
    }

    @Composable
    private fun ReactionTailRow(
        index: Int,
        measuredReactionProjection: Int,
        tagPrefix: String,
    ) {
        val isTail = index == REACTION_TAIL_ROW_COUNT - 1
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (isTail) Modifier.testTag("$tagPrefix-$REACTION_TAIL_ROW_TAG") else Modifier),
        ) {
            Spacer(Modifier.fillMaxWidth().height(56.dp))
            if (isTail && measuredReactionProjection > 0) {
                Spacer(Modifier.fillMaxWidth().height(28.dp))
            }
        }
    }

    @Composable
    private fun BottomChromeScaffold(
        listState: LazyListState,
        timelineSize: Int,
        bottomChromeHeight: Dp,
        onHeightChanged: (Int) -> Unit,
    ) {
        Box(Modifier.size(width = 320.dp, height = 500.dp)) {
            Scaffold(
                bottomBar = {
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(bottomChromeHeight)
                            .onSizeChanged { onHeightChanged(it.height) }
                            .testTag(BOTTOM_CHROME_TAG),
                    )
                },
            ) { padding ->
                LazyColumn(
                    state = listState,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                ) {
                    items((0 until timelineSize).toList()) { index ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(70.dp)
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
        }
    }

    private fun scrollTo(
        state: LazyListState,
        index: Int,
        offset: Int = 0,
    ) {
        composeRule.runOnUiThread {
            runBlocking {
                state.scrollToItem(index, offset)
            }
        }
        composeRule.waitForIdle()
    }

    private class CountingLazyListScrollWriter(
        private val listState: LazyListState,
    ) : ConversationScrollWriter {
        var snapCount = 0
            private set

        override val firstVisibleItemIndex: Int
            get() = listState.firstVisibleItemIndex

        override suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            snapCount++
            listState.scrollToItem(index, scrollOffset)
        }

        override suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int,
        ) {
            listState.animateScrollToItem(index, scrollOffset)
        }
    }

    private companion object {
        const val TAIL_ROW_TAG = "bottom-chrome-tail-row"
        const val BOTTOM_CHROME_TAG = "conversation-bottom-chrome"
        const val REACTION_TAIL_ROW_COUNT = 8
        const val REACTION_TAIL_ROW_TAG = "reaction-tail-row"
        const val REACTION_COMPOSER_TAG = "reaction-composer"
    }
}
