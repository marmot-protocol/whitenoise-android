package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The actual toolbar retains exactly one editable field while decorative geometry reverses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatListSearchMotionTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Input is admitted on the first frame; Back retires it without clearing caller-owned query. */
    @Test
    fun rapidSearchBackKeepsOneFieldAndTheExistingQueryOwner() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appState = ChatRowPortFixtures.state(context)
        val open = mutableStateOf(false)
        val query = mutableStateOf("existing")
        val requester = FocusRequester()
        composeRule.setContent {
            WhiteNoiseTheme {
                ChatListTopBar(
                    appState = appState,
                    searchOpen = open.value,
                    searchQuery = query.value,
                    searchFocusRequester = requester,
                    onSearchQueryChange = { query.value = it },
                    onSearchOpen = { open.value = true },
                    onSearchClose = { open.value = false },
                    onMic = {},
                    onOpenSettings = {},
                    onSwitchAccount = {},
                )
            }
        }
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_list_search_open)).performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        composeRule.onNodeWithTag("chats.searchField").performTextReplacement("retained query")
        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertEquals("retained query", query.value)
        composeRule.onNodeWithContentDescription(context.getString(R.string.chat_list_search_open)).performClick()
        composeRule.mainClock.advanceTimeBy(240)
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        assertEquals("retained query", query.value)
    }
}
