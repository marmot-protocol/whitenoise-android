package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.ipf.whitenoise.android.media.GiphySearchResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class GiphySearchDebounceTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val composeRule = createComposeRule(effectContext = StandardTestDispatcher(scheduler))

    /** Only settled text is sent. A replaced request is cancelled before its result can appear. */
    @Test
    fun debouncesTypingAndCancelsAnInFlightQuery() {
        val query = mutableStateOf("")
        val requested = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val search: suspend (String) -> List<GiphySearchResult> = { text ->
            requested += text
            if (text == "cat") {
                try {
                    awaitCancellation()
                } finally {
                    cancelled += text
                }
            }
            emptyList()
        }

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val state = rememberGiphySearchState(query.value, 0, search)
            Text(if (state is GiphySearchState.Results) "Results ready" else "Waiting")
        }
        type(query, "c")
        composeRule.mainClock.advanceTimeBy(150)
        type(query, "cat")
        composeRule.mainClock.advanceTimeBy(250)
        composeRule.runOnIdle { assertEquals(emptyList<String>(), requested) }
        composeRule.mainClock.advanceTimeBy(60)
        composeRule.runOnIdle { assertEquals(listOf("cat"), requested) }

        type(query, "cats")
        composeRule.mainClock.advanceTimeBy(GIPHY_SEARCH_DEBOUNCE_MS + 50)
        composeRule.runOnIdle {
            assertEquals(listOf("cat"), cancelled)
            assertEquals(listOf("cat", "cats"), requested)
        }
        composeRule.onNodeWithText("Results ready").assertIsDisplayed()
    }

    /** Applies a text edit and commits the recomposition before advancing the debounce clock. */
    private fun type(
        query: MutableState<String>,
        text: String,
    ) {
        composeRule.runOnIdle {
            query.value = text
            Snapshot.sendApplyNotifications()
        }
        scheduler.runCurrent()
        composeRule.mainClock.advanceTimeByFrame()
        scheduler.runCurrent()
        composeRule.waitForIdle()
    }
}
