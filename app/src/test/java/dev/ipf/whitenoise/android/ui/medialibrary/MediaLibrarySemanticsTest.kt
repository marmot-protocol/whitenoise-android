package dev.ipf.whitenoise.android.ui.medialibrary

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Uses the real pager and production entry/footer to traverse history without opening a chat timeline. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaLibrarySemanticsTest {
    @get:Rule val composeRule = createComposeRule()

    /** The zero-recent-content entry opens bounded discovery, and Retry resumes its exact continuation. */
    @Test
    fun olderAttachmentAccessibleWithoutScrollingChat() {
        val source = GroupAttachmentFixture(listOf(historyEntry(240), historyEntry(1)), size = 1)
        val pager = GroupAttachmentPager(source)
        composeRule.setContent {
            WhiteNoiseTheme {
                var opened by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                if (!opened) {
                    Column {
                        SharedMediaSection(emptySharedMediaTiles(), {
                            opened = true
                            scope.launch { pager.refresh() }
                        })
                    }
                } else {
                    Column {
                        pager.state.entries.forEach { androidx.compose.material3.Text(it.messageIdHex) }
                        AttachmentLibraryFooter(pager.state) { scope.launch { pager.loadMore() } }
                    }
                }
            }
        }
        composeRule
            .onNodeWithTag("shared.category.Documents")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.onNodeWithText("message-240").assertIsDisplayed()
        source.failNext = true
        composeRule.onNodeWithTag("shared.history.more").performClick()
        composeRule.onNodeWithTag("shared.history.retry").assertHasClickAction().performClick()
        composeRule.onNodeWithText("message-1").assertIsDisplayed()
        composeRule.onNodeWithText("End of local attachment history").assertIsDisplayed()
        composeRule.onNodeWithTag("shared.history.more").assertDoesNotExist()
        assertEquals(3, source.calls)
        pager.close()
    }
}
