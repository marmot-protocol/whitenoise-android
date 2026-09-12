package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Native folder IDs stay selected through scrolling/reorder; geometry never clears filters or edits membership. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatFolderPillsBehaviorTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun initiallySelectedDistantFolderIsRevealedAndExplicitChatsResetsSelection() {
        var selected by mutableStateOf<String?>("folder-9")
        var selections = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ChatFolderPills(folders(), selected, {
                        selected = it
                        selections++
                    }, {}, {})
                }
            }
        }
        composeRule.onNodeWithTag(chatListFilterChipTag("folder-9")).assertIsDisplayed().assertIsSelected()
        assertEquals(0, selections)
        composeRule.runOnIdle { selected = null }
        composeRule
            .onNodeWithTag(CHAT_LIST_FILTER_CHIP_ALL_TAG)
            .assertIsDisplayed()
            .assertIsSelected()
            .performClick()
        assertEquals(null, selected)
        assertEquals(1, selections)
    }

    @Test
    @Config(sdk = [36], qualifiers = "ar-ldrtl-w360dp-h780dp-mdpi")
    fun selectedFolderIsRevealedInRtl() = initiallySelectedDistantFolderIsRevealedAndExplicitChatsResetsSelection()

    @Test fun reorderedSelectedFolderRemainsVisibleAndRetapDoesNotToggleOff() {
        var chips by mutableStateOf(folders())
        var selected by mutableStateOf<String?>("folder-1")
        composeRule.setContent {
            WhiteNoiseTheme { Surface { ChatFolderPills(chips, selected, { selected = it }, {}, {}) } }
        }
        composeRule.runOnIdle { chips = chips.filterNot { it.folderId == "folder-1" } + chips[1] }
        composeRule
            .onNodeWithTag(chatListFilterChipTag("folder-1"))
            .assertIsDisplayed()
            .assertIsSelected()
            .performClick()
        assertEquals("folder-1", selected)
    }

    @Test fun currentEditAndManageCallbacksKeepExactFolderSelection() {
        var latest by mutableStateOf(false)
        var selected: String? = "folder-0"
        val edits = mutableListOf<String>()
        var managed = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ChatFolderPills(
                        folders().take(1),
                        selected,
                        { selected = it },
                        onEditFolder = if (latest) ({ edits += "current:$it" }) else ({ edits += "old:$it" }),
                        onFolders = { managed++ },
                    )
                }
            }
        }
        composeRule.runOnIdle { latest = true }
        composeRule
            .onNodeWithTag(chatListFilterChipTag("folder-0"))
            .performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.onNodeWithTag("chats.folders").performScrollToIndex(2)
        composeRule.onNodeWithTag("chats.manageFolders").performClick()
        assertEquals(listOf("current:folder-0"), edits)
        assertEquals(1, managed)
        assertEquals("folder-0", selected)
    }

    private fun folders() = (0..9).map { ChatFolderChipModel("folder-$it", null, "Folder $it long label", it) }
}
