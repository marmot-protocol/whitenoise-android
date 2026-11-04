package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The transcript's prefetch strategy measures a row the list never laid out without touching the rows on screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@OptIn(ExperimentalFoundationApi::class)
class ConversationTranscriptPrefetchTest {
    @get:Rule val composeRule = createComposeRule()

    /**
     * An off-screen row reports the height a layout would give it, while the list stays where it was and the visible
     * rows are neither disposed nor composed again.
     */
    @Test
    fun premeasureReportsAnOffScreenRowWithoutMovingOrRecomposingTheList() {
        val scheduler = FramePrefetchScheduler()
        val strategy = ConversationTranscriptPrefetchStrategy(scheduler)
        var newestRowEntered = 0
        var newestRowLeft = 0
        lateinit var listState: LazyListState
        composeRule.setContent {
            LaunchedEffect(scheduler) { scheduler.run() }
            listState = rememberLazyListState(prefetchStrategy = strategy)
            Box(Modifier.height(300.dp)) {
                LazyColumn(state = listState, reverseLayout = true) {
                    items(ROW_COUNT) { index ->
                        if (index == 0) {
                            DisposableEffect(Unit) {
                                newestRowEntered++
                                onDispose { newestRowLeft++ }
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(if (index == TALL_INDEX) 900.dp else 100.dp))
                    }
                }
            }
        }
        composeRule.waitForIdle()
        var measured: Int? = null
        var release: (() -> Unit)? = null
        composeRule.runOnUiThread { release = strategy.premeasure(TALL_INDEX) { measured = it } }
        composeRule.waitForIdle()
        assertEquals(900, measured)
        assertEquals(0, listState.firstVisibleItemIndex)
        assertEquals(0, listState.firstVisibleItemScrollOffset)
        assertEquals("the newest row stayed composed", 1 to 0, newestRowEntered to newestRowLeft)
        composeRule.runOnUiThread { requireNotNull(release).invoke() }
    }

    /** Before the list has laid out it has lent no prefetch scope, so nothing can be measured yet. */
    @Test
    fun premeasureBeforeTheFirstLayoutMeasuresNothing() {
        assertNull(ConversationTranscriptPrefetchStrategy().premeasure(0) { error("measured without a layout") })
    }

    private companion object {
        const val ROW_COUNT = 50
        const val TALL_INDEX = 40
    }
}
