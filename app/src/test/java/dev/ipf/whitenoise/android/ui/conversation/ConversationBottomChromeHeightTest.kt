package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
    ) {
        composeRule.runOnUiThread {
            runBlocking {
                state.scrollToItem(index)
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val TAIL_ROW_TAG = "bottom-chrome-tail-row"
        const val BOTTOM_CHROME_TAG = "conversation-bottom-chrome"
    }
}
