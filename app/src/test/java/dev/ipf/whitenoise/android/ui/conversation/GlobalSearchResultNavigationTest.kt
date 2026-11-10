package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Landing on a distant global-search result composes only the rows near it, never the history between (#1945).
 * This drives the scroll coordinator and the Compose list writer that ConversationScreen's centering uses, over a
 * plain list of rows; mounting the whole conversation screen with a scripted multi-thousand-row history is not
 * practical here, so the screen's own wiring of that centering is covered by the landing tests instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GlobalSearchResultNavigationTest {
    @get:Rule val composeRule = createComposeRule()

    /** A jump from the newest rows to one 1,500 rows back composes a bounded set of rows around both ends. */
    @Test
    fun distantResultDoesNotComposeTheInterveningHistory() {
        val composed = sortedSetOf<Int>()
        lateinit var coordinator: ConversationScrollCoordinator
        lateinit var scope: CoroutineScope
        composeRule.setContent {
            val listState = rememberLazyListState()
            scope = rememberCoroutineScope()
            coordinator =
                remember(listState) {
                    ConversationScrollCoordinator(
                        writer = LazyListConversationScrollWriter(listState),
                        initialMode = ConversationScrollMode.FollowingTail,
                    )
                }
            LazyColumn(state = listState, modifier = Modifier.height(ROW_HEIGHT * VISIBLE_ROWS)) {
                items(TOTAL_ROWS, key = { it }) { index ->
                    composed += index
                    Text("Row $index", Modifier.height(ROW_HEIGHT))
                }
            }
        }
        composeRule.waitForIdle()
        composed.clear()

        var landed = false
        composeRule.runOnIdle {
            scope.launch {
                landed =
                    coordinator.programmaticJump("result", ConversationScrollReason.FocusMessage) {
                        animateScrollToItem(TARGET)
                    }
            }
        }
        composeRule.waitUntil(WAIT_MILLIS) { landed }

        assertTrue("the result row is composed", TARGET in composed)
        assertFalse("the middle of the history is never composed", TOTAL_ROWS / 2 in composed)
        assertTrue("only rows near the destination are composed, not ${composed.size}", composed.size < MAX_COMPOSED)
    }

    private companion object {
        const val TOTAL_ROWS = 2_000
        const val TARGET = 1_500
        const val VISIBLE_ROWS = 8
        const val MAX_COMPOSED = 60
        const val WAIT_MILLIS = 10_000L
        val ROW_HEIGHT = 48.dp
    }
}
