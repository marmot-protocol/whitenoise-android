package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Local names remain available while the actual asynchronous body producer is delayed or fails. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class GlobalSearchBodyStageTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun nameResultIsVisibleBeforeDebounceAndWhileBodyLookupIsSuspended() {
        val body = CompletableDeferred<String>()
        var failed = false
        val request = mutableStateOf(Any())
        var retried = false
        composeRule.setContent {
            val result = rememberGlobalSearchLookup(request.value, true, "GLOBAL_BODY_SEARCH") { body.await() }
            SideEffect { failed = result?.error != null }
            MaterialTheme {
                if (!blockLocalSearchRows(result == null, hasLocalRows = true, preservePendingAnchor = false)) {
                    Column {
                        Text("Local group name")
                        GlobalSearchBodyStage(result == null, result?.error) {
                            retried = true
                            request.value = Any()
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithText("Local group name").assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(CHAT_LIST_SEARCH_DEBOUNCE_MS)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Local group name").assertIsDisplayed()
        composeRule.runOnIdle { body.completeExceptionally(IllegalStateException("private body query")) }
        composeRule.waitUntil(20_000L) { failed }
        composeRule.onNodeWithText("Local group name").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.runOnIdle { assertTrue(retried) }
        composeRule.onNodeWithText("Local group name").assertIsDisplayed()
    }

    @Test
    fun freshQueryDoesNotInheritOldDatasetDeepPosition() {
        assertFalse(preserveSearchBodyAnchor(sameDataset = false, firstVisibleIndex = 45, firstVisibleOffset = 7))
        assertFalse(blockLocalSearchRows(loading = true, hasLocalRows = true, preservePendingAnchor = false))
    }

    @Test
    fun sameDatasetDeepRestorationWaitsWithoutClampingButCompletionUnblocks() {
        val preserve = preserveSearchBodyAnchor(sameDataset = true, firstVisibleIndex = 45, firstVisibleOffset = 7)
        assertTrue(preserve)
        assertTrue(blockLocalSearchRows(loading = true, hasLocalRows = true, preservePendingAnchor = preserve))
        assertFalse(blockLocalSearchRows(loading = false, hasLocalRows = true, preservePendingAnchor = preserve))
        assertTrue(preserveSearchBodyAnchor(sameDataset = true, firstVisibleIndex = 0, firstVisibleOffset = 7))
        assertFalse(preserveSearchBodyAnchor(sameDataset = true, firstVisibleIndex = 0, firstVisibleOffset = 0))
    }

    @Test
    fun messageOnlySearchWithNoEligibleLocalRowsWaitsForItsRealMatches() {
        assertTrue(blockLocalSearchRows(loading = true, hasLocalRows = false, preservePendingAnchor = false))
        assertFalse(blockLocalSearchRows(loading = false, hasLocalRows = false, preservePendingAnchor = false))
    }
}
