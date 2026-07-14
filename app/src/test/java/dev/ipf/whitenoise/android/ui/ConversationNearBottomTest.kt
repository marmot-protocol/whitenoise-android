package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.conversation.isNearBottom
import dev.ipf.whitenoise.android.ui.conversation.rememberConversationNearBottom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the conversation near-bottom derived state (issue #1253).
 *
 * The jump-to-newest FAB keys off near-bottom; that flag must track async
 * timeline hydration, not a zero size captured on first composition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationNearBottomTest {
    @get:Rule
    val composeRule = createComposeRule()

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
            LazyColumn(
                modifier = Modifier.height(100.dp),
                state = listState,
            ) {
                item { Spacer(Modifier.height(1.dp)) }
                item { Spacer(Modifier.height(1.dp)) }
                items((0 until 50).toList()) {
                    Box(Modifier.fillMaxWidth().height(50.dp))
                }
                item { Spacer(Modifier.height(1.dp)) }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            timelineSize.value = 50
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            runBlocking {
                listState.scrollToItem(20)
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            assertFalse(
                "Jump FAB should show when scrolled up after timeline hydration",
                nearBottomHolder[0]!!,
            )
        }
    }

    /**
     * IME-open chase gates on near-bottom (issue #1375). While the keyboard
     * shrinks the viewport, LazyListState can transiently report
     * canScrollForward=false even though the last visible row is still history.
     * isNearBottom must not treat that as "at bottom".
     */
    @Test
    fun isNearBottomFalseWhenCannotScrollForwardButViewingHistory() {
        val listState = LazyListState()
        val loadedTimelineCount = 22
        val fullTimelineSize = 50

        composeRule.setContent {
            LazyColumn(
                modifier = Modifier.height(220.dp),
                state = listState,
            ) {
                item { Spacer(Modifier.height(1.dp)) }
                item { Spacer(Modifier.height(1.dp)) }
                items((0 until loadedTimelineCount).toList()) {
                    Box(Modifier.fillMaxWidth().height(50.dp))
                }
                item { Spacer(Modifier.height(1.dp)) }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            runBlocking {
                listState.scrollToItem(20)
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            assertFalse(
                "Scrolled-up reader must not be treated as near bottom when the " +
                    "lazy list end is visible but newer messages exist off-screen",
                isNearBottom(
                    listState = listState,
                    timelineSize = fullTimelineSize,
                    hasOlderHeader = true,
                ),
            )
        }
    }
}
