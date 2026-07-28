package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ChatFolderEditContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun saveDisabledWhenNameBlank() {
        render(state = editState(name = "   "))

        composeRule.onNodeWithText(app.getString(R.string.save)).assertIsNotEnabled()
    }

    @Test
    fun editorUsesScreenTitleAndNeutralDetailsSectionTitle() {
        render(state = editState(name = "Work"))

        composeRule
            .onAllNodesWithText(app.getString(R.string.chat_folder_new))
            .assertCountEquals(1)
        composeRule.onNodeWithText(app.getString(R.string.details)).assertIsDisplayed()
    }

    @Test
    fun saveEnabledWhenNameNonBlank() {
        render(state = editState(name = "Work"))

        composeRule.onNodeWithText(app.getString(R.string.save)).assertIsEnabled()
    }

    @Test
    fun switchRowsExposeLabelsAndCheckedState() {
        render(
            state =
                editState(
                    name = "Work",
                    unreadOnly = true,
                    groupsOnly = true,
                    archivedOnly = false,
                    includeMuted = false,
                ),
        )

        composeRule
            .onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG)
            .performScrollToNode(hasText(app.getString(R.string.chat_folder_unread_only)))
        composeRule
            .onNodeWithText(app.getString(R.string.chat_folder_unread_only))
            .assertIsDisplayed()
        composeRule.onAllNodes(isToggleable())[0].assertIsOn()
        composeRule
            .onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG)
            .performScrollToNode(hasText(app.getString(R.string.chat_folder_groups_only)))
        composeRule
            .onNodeWithText(app.getString(R.string.chat_folder_groups_only))
            .assertIsDisplayed()
        composeRule.onAllNodes(isToggleable())[1].assertIsOn()
        composeRule
            .onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG)
            .performScrollToNode(hasText(app.getString(R.string.chat_folder_archived_only)))
        composeRule.onAllNodes(isToggleable())[2].assertIsOff()
        composeRule
            .onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG)
            .performScrollToNode(hasText(app.getString(R.string.chat_folder_include_muted)))
        composeRule
            .onNodeWithText(app.getString(R.string.chat_folder_include_muted))
            .assertIsDisplayed()
        composeRule.onAllNodes(isToggleable())[3].assertIsOff()
    }

    @Test
    fun manualChatsRowOpensPicker() {
        var opened = false
        render(
            state = editState(name = "Work"),
            onOpenManualChats = { opened = true },
        )

        composeRule
            .onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG)
            .performScrollToNode(hasText("0 chats", substring = true))
        composeRule
            .onAllNodes(
                hasText(app.getString(R.string.chat_folder_manual_chats)) and
                    hasText("0 chats", substring = true) and
                    hasClickAction(),
            )[0]
            .performClick()

        assertEquals(true, opened)
    }

    @Test
    fun peopleRowOpensPicker() {
        var opened = false
        render(
            state = editState(name = "Work"),
            onOpenPeople = { opened = true },
        )

        composeRule
            .onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG)
            .performScrollToNode(hasText(app.getString(R.string.chat_folder_people_subtitle)))
        composeRule
            .onAllNodes(
                hasText(app.getString(R.string.chat_folder_people_subtitle)) and hasClickAction(),
            )[0]
            .performClick()

        assertEquals(true, opened)
    }

    private fun editState(
        name: String,
        description: String = "",
        keyword: String = "",
        unreadOnly: Boolean = false,
        includeMuted: Boolean = false,
        groupsOnly: Boolean = false,
        archivedOnly: Boolean = false,
        manualChatCount: Int = 0,
        peopleSummary: String = app.getString(R.string.chat_folder_people_subtitle),
        isNew: Boolean = true,
    ) = ChatFolderEditFormState(
        isNew = isNew,
        name = name,
        description = description,
        keyword = keyword,
        unreadOnly = unreadOnly,
        includeMuted = includeMuted,
        groupsOnly = groupsOnly,
        archivedOnly = archivedOnly,
        manualChatSummary =
            app.resources.getQuantityString(
                R.plurals.chat_folder_chat_count,
                manualChatCount,
                manualChatCount,
            ),
        peopleSummary = peopleSummary,
        canSave = name.isNotBlank(),
    )

    private fun render(
        state: ChatFolderEditFormState,
        onNameChange: (String) -> Unit = {},
        onDescriptionChange: (String) -> Unit = {},
        onKeywordChange: (String) -> Unit = {},
        onUnreadOnlyChange: (Boolean) -> Unit = {},
        onIncludeMutedChange: (Boolean) -> Unit = {},
        onGroupsOnlyChange: (Boolean) -> Unit = {},
        onArchivedOnlyChange: (Boolean) -> Unit = {},
        onOpenManualChats: () -> Unit = {},
        onOpenPeople: () -> Unit = {},
        onSave: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(modifier = Modifier.fillMaxSize().height(640.dp)) {
                    ChatFolderEditContent(
                        state = state,
                        onNameChange = onNameChange,
                        onDescriptionChange = onDescriptionChange,
                        onKeywordChange = onKeywordChange,
                        onUnreadOnlyChange = onUnreadOnlyChange,
                        onIncludeMutedChange = onIncludeMutedChange,
                        onGroupsOnlyChange = onGroupsOnlyChange,
                        onArchivedOnlyChange = onArchivedOnlyChange,
                        onOpenManualChats = onOpenManualChats,
                        onOpenPeople = onOpenPeople,
                        onSave = onSave,
                        onBack = onBack,
                    )
                }
            }
        }
    }
}
