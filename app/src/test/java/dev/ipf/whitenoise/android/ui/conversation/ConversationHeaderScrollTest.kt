package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.LocalWhiteNoiseHeaderScroll
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseScaffold
import dev.ipf.whitenoise.android.ui.common.trackWhiteNoiseHeader
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Native chronological transcript position owns tint across restoration, navigation and compact chrome. */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationHeaderScrollTest {
    @get:Rule val composeRule = createComposeRule()

    private lateinit var list: LazyListState
    private lateinit var behavior: TopAppBarScrollBehavior
    private lateinit var scope: CoroutineScope
    private var compact by mutableStateOf(false)
    private var count by mutableStateOf(30)

    /** Changing measured header height must retain full tint without moving an idle restored transcript. */
    @Test fun restoredHistoryRetainsTintAcrossCompactHeightChanges() {
        compact = true
        mountTranscript()
        val compactLimit = composeRule.runOnIdle { behavior.state.heightOffsetLimit }
        composeRule.runOnIdle {
            assertEquals(4, list.firstVisibleItemIndex)
            assertEquals(11, list.firstVisibleItemScrollOffset)
            assertFalse(list.isScrollInProgress)
            // Nested-scroll accumulation can leave a subpixel floating-point remainder.
            assertEquals(1f, behavior.state.overlappedFraction, 0.0001f)
            compact = false
        }
        composeRule.runOnIdle {
            assertTrue(behavior.state.heightOffsetLimit < compactLimit)
            assertEquals(1f, behavior.state.overlappedFraction, 0f)
            assertEquals(4, list.firstVisibleItemIndex)
            assertEquals(11, list.firstVisibleItemScrollOffset)
            compact = true
        }
        composeRule.runOnIdle {
            assertEquals(compactLimit, behavior.state.heightOffsetLimit, 0.1f)
            assertEquals(1f, behavior.state.overlappedFraction, 0f)
            assertEquals(4, list.firstVisibleItemIndex)
            assertEquals(11, list.firstVisibleItemScrollOffset)
        }
    }

    /** Real programmatic navigation and an underfilled refresh restore the same owner's resting tint. */
    @Test fun programmaticHistoryAndTailNavigationTrackTheActualList() {
        mountTranscript()
        scrollTo(0)
        composeRule.runOnIdle {
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0f, behavior.state.contentOffset, 0f)
        }
        scrollTo(29)
        composeRule.runOnIdle {
            assertTrue(list.firstVisibleItemIndex > 0)
            assertFalse(list.canScrollForward)
            assertEquals(1f, behavior.state.overlappedFraction, 0f)
            count = 1
        }
        composeRule.runOnIdle {
            assertFalse(list.canScrollBackward)
            assertFalse(list.canScrollForward)
            assertEquals(0f, behavior.state.contentOffset, 0f)
        }
    }

    /** Native nested-scroll writes must not clear tint while a touch drag still leaves older content above. */
    @Test fun downwardDragWithinHistoryKeepsTheHeaderTinted() {
        count = 100
        mountTranscript()
        scrollTo(20)
        val initialIndex = composeRule.runOnIdle { list.firstVisibleItemIndex }
        composeRule.onNodeWithTag("header-scroll-transcript").performTouchInput {
            swipeDown(durationMillis = 1_000)
        }
        composeRule.runOnIdle {
            assertTrue(list.firstVisibleItemIndex > 0)
            assertTrue(initialIndex - list.firstVisibleItemIndex >= 2)
            assertFalse(list.isScrollInProgress)
            // Nested-scroll accumulation can leave a subpixel floating-point remainder.
            assertEquals(1f, behavior.state.overlappedFraction, 0.0001f)
        }
    }

    /** Uses the app scaffold, a measured pinned header and the transcript's actual ordering and spacing. */
    private fun mountTranscript() {
        composeRule.setContent {
            WhiteNoiseTheme {
                list = rememberLazyListState(initialFirstVisibleItemIndex = 4, initialFirstVisibleItemScrollOffset = 11)
                scope = rememberCoroutineScope()
                WhiteNoiseScaffold(
                    topBar = {
                        behavior = requireNotNull(LocalWhiteNoiseHeaderScroll.current)
                        TopAppBar(
                            title = { Text("Conversation") },
                            expandedHeight = if (compact) 48.dp else 64.dp,
                            scrollBehavior = behavior,
                        )
                    },
                ) { padding ->
                    LazyColumn(
                        state = list,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .testTag("header-scroll-transcript")
                                .trackWhiteNoiseHeader(list),
                        verticalArrangement = CONVERSATION_TIMELINE_VERTICAL_ARRANGEMENT,
                        contentPadding = conversationTimelineContentPadding(0.dp),
                    ) {
                        items(count, key = { it }) { Text("Message $it", Modifier.height(48.dp)) }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Moves only through the real list API; no synthetic header offset or nested-scroll event is injected. */
    private fun scrollTo(index: Int) {
        composeRule.runOnIdle { scope.launch { list.scrollToItem(index) } }
        composeRule.waitForIdle()
    }
}
