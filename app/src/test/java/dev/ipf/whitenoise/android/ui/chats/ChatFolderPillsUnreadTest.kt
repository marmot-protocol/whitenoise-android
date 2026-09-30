package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Current-account unread messages use a separate ULong presentation from custom-folder chat counts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatFolderPillsUnreadTest {
    @get:Rule val composeRule = createComposeRule()

    /** Zero is silent; selected folder changes cannot remove the Chats total. */
    @Test fun countPersistsAcrossFolderSelectionAndHidesZero() {
        var selected by mutableStateOf<String?>(null)
        var count by mutableStateOf<ULong?>(0uL)
        val chip = ChatFolderChipModel("work", null, "Work", 2)
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ChatFolderPills(listOf(chip), selected, { selected = it }, {}, {}, chatsUnreadCount = count)
                }
            }
        }
        composeRule.onNodeWithTag(CHAT_LIST_FILTER_CHIP_ALL_TAG).assertContentDescriptionEquals("Chats")
        composeRule.runOnIdle { count = 1uL }
        composeRule
            .onNodeWithTag(CHAT_LIST_FILTER_CHIP_ALL_TAG)
            .assertContentDescriptionEquals("Chats, 1 unread message")
        composeRule.onNodeWithTag(chatListFilterChipTag("work")).performClick().assertIsSelected()
        composeRule
            .onNodeWithTag(CHAT_LIST_FILTER_CHIP_ALL_TAG)
            .assertIsDisplayed()
            .assertContentDescriptionEquals("Chats, 1 unread message")
        composeRule.onNodeWithTag(chatListFilterChipTag("work")).assertContentDescriptionEquals("Work, 2 chats")
        composeRule.runOnIdle { count = 99uL }
        composeRule
            .onNodeWithTag(CHAT_LIST_FILTER_CHIP_ALL_TAG)
            .assertContentDescriptionEquals("Chats, 99 unread messages")
        composeRule.runOnIdle { count = 100uL }
        assertTrue(chatsPillVisibleCount(100uL) == "99+")
        composeRule
            .onNodeWithTag(CHAT_LIST_FILTER_CHIP_ALL_TAG)
            .assertContentDescriptionEquals("Chats, 100 unread messages")
        composeRule.runOnIdle { count = null }
        composeRule.onNodeWithTag(CHAT_LIST_FILTER_CHIP_ALL_TAG).assertContentDescriptionEquals("Chats")
    }

    /** Large native values retain their full decimal text and exact locale plural category. */
    @Test fun fullUnsignedCountIsNotNarrowed() {
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val description = chatsPillAccessibleDescription(resources, "Chats", ULong.MAX_VALUE)
        assertTrue(description.contains("18,446,744,073,709,551,615"))
        assertTrue(description.endsWith("unread messages"))
        assertTrue(chatsPillVisibleCount(ULong.MAX_VALUE) == "99+")
        assertTrue(R.plurals.chat_folder_chat_count != R.plurals.chat_pill_unread_messages_count)
    }

    /** Russian one/few/many categories remain correct above the 32-bit quantity range. */
    @Test
    @Config(sdk = [36], qualifiers = "ru-w360dp-h780dp-mdpi")
    fun russianLargePluralCategory() {
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val description = chatsPillAccessibleDescription(resources, "Чаты", 4_294_967_301uL)
        assertTrue(description.contains("непрочитанное сообщение"))
    }

    /** API 30's double-only ICU selector still uses exact Russian last digits beyond 2^53. */
    @Test
    @Config(sdk = [30], qualifiers = "ru-w360dp-h780dp-mdpi")
    fun api30LargeRussianCountsKeepPluralCategories() {
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val one = chatsPillAccessibleDescription(resources, "Чаты", 9_007_199_254_741_001uL)
        val few = chatsPillAccessibleDescription(resources, "Чаты", 9_007_199_254_741_002uL)
        val many = chatsPillAccessibleDescription(resources, "Чаты", 9_007_199_254_741_005uL)
        assertTrue(one.endsWith("непрочитанное сообщение"))
        assertTrue(few.endsWith("непрочитанных сообщения"))
        assertTrue(many.endsWith("непрочитанных сообщений"))
    }
}
