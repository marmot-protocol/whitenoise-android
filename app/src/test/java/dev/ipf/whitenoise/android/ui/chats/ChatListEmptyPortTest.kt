package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Empty presentation does not lose its create action or confuse archived, filtered and searched scopes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatListEmptyPortTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** New account retains the actual create callback. */
    @Test fun newAccountRetainsTheActualCreateCallback() {
        var creates = 0
        composeRule.setContent { WhiteNoiseTheme { EmptyChats(onCreate = { creates++ }) } }
        composeRule.onNodeWithText(context.getString(R.string.chat_rows_no_chats_title)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.new_chat)).performClick()
        assertEquals(1, creates)
    }

    /** Search empty takes precedence over unread folder. */
    @Test fun searchEmptyTakesPrecedenceOverUnreadFolder() {
        composeRule.setContent { WhiteNoiseTheme { ChatListNoResults("not found", true) } }
        composeRule.onNodeWithText(context.getString(R.string.no_results)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.chat_rows_no_unread_title)).assertDoesNotExist()
    }

    /** Unread empty does not claim the account has no chats. */
    @Test fun unreadEmptyDoesNotClaimTheAccountHasNoChats() {
        composeRule.setContent { WhiteNoiseTheme { ChatListNoResults("", true) } }
        composeRule.onNodeWithText(context.getString(R.string.chat_rows_no_unread_detail)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.chat_rows_no_chats_title)).assertDoesNotExist()
    }

    /** Archived empty keeps its own scope. */
    @Test fun archivedEmptyKeepsItsOwnScope() {
        composeRule.setContent { WhiteNoiseTheme { EmptyArchivedChats() } }
        composeRule.onNodeWithText(context.getString(R.string.chat_rows_no_archived_title)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.chat_rows_no_chats_title)).assertDoesNotExist()
    }
}
