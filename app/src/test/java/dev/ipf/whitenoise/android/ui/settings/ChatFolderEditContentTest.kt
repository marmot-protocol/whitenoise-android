package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Contract of the folder editor form: Save gating, rule switches, and the rows that open pickers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h1400dp-mdpi")
class ChatFolderEditContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Context>()

    /** Save is disabled while the name is blank. */
    @Test
    fun saveDisabledWhenNameBlank() {
        render(state = editState(name = "   "))
        composeRule.onNodeWithText(app.getString(R.string.save)).assertIsNotEnabled()
    }

    /** The screen title reads New folder exactly once; there is no separate details heading. */
    @Test
    fun editorUsesTheScreenTitleOnly() {
        render(state = editState(name = "Work"))
        composeRule.onAllNodesWithText(app.getString(R.string.folder_new_title)).assertCountEquals(1)
        composeRule.onAllNodesWithText(app.getString(R.string.details)).assertCountEquals(0)
    }

    /** Save is enabled once the name has text. */
    @Test
    fun saveEnabledWhenNameNonBlank() {
        render(state = editState(name = "Work"))
        composeRule.onNodeWithText(app.getString(R.string.save)).assertIsEnabled()
    }

    /** The four rule switches carry their labels and checked state in prototype order. */
    @Test
    fun switchRowsExposeLabelsAndCheckedState() {
        render(state = editState(name = "Work", unreadOnly = true, groupsOnly = true))
        composeRule
            .onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG)
            .performScrollToNode(hasText(app.getString(R.string.chat_folder_include_muted)))
        composeRule.onNodeWithText(app.getString(R.string.chat_folder_unread_only)).assertExists()
        composeRule.onAllNodes(isToggleable())[0].assertIsOn()
        composeRule.onNodeWithText(app.getString(R.string.chat_folder_groups_only)).assertExists()
        composeRule.onAllNodes(isToggleable())[1].assertIsOn()
        composeRule.onAllNodes(isToggleable())[2].assertIsOff()
        composeRule.onNodeWithText(app.getString(R.string.chat_folder_include_muted)).assertExists()
        composeRule.onAllNodes(isToggleable())[3].assertIsOff()
    }

    /** Included Chats shows its count and opens the chat picker. */
    @Test
    fun manualChatsRowOpensPicker() {
        var opened = false
        render(state = editState(name = "Work", manualChatCount = 3), onOpenManualChats = { opened = true })
        composeRule
            .onAllNodes(
                hasText(app.getString(R.string.folder_included_chats)) and hasText("3") and hasClickAction(),
            )[0]
            .performClick()
        assertEquals(true, opened)
    }

    /** People shows its count and opens the people picker. */
    @Test
    fun peopleRowOpensPicker() {
        var opened = false
        render(state = editState(name = "Work", peopleCount = 2), onOpenPeople = { opened = true })
        composeRule
            .onAllNodes(hasText(app.getString(R.string.chat_folder_people)) and hasText("2") and hasClickAction())[0]
            .performClick()
        assertEquals(true, opened)
    }

    /** Preview shows how many chats the draft matches and opens the preview list. */
    @Test
    fun previewRowShowsCountAndOpens() {
        var opened = false
        render(state = editState(name = "Work", previewCount = 4), onOpenPreview = { opened = true })
        val count = app.resources.getQuantityString(R.plurals.chat_folder_chat_count, 4, 4)
        composeRule.onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG).performScrollToNode(hasText(count))
        composeRule
            .onAllNodes(hasText(app.getString(R.string.folder_preview)) and hasText(count) and hasClickAction())[0]
            .performClick()
        assertEquals(true, opened)
    }

    /** An error message renders beneath the fields in the error colour. */
    @Test
    fun errorTextIsShownWhenPresent() {
        render(state = editState(name = "Work", error = app.getString(R.string.folder_save_failed)))
        composeRule.onNodeWithText(app.getString(R.string.folder_save_failed)).assertExists()
    }

    @Test
    fun deleteActionIsHiddenForNewFolders() {
        render(state = editState(name = "New", isNew = true))
        composeRule.onNodeWithTag("folder.delete").assertDoesNotExist()
    }

    @Test
    fun deleteActionIsAvailableForExistingFolders() {
        var deleteRequested = false
        render(state = editState(name = "Work", isNew = false), onDelete = { deleteRequested = true })
        composeRule.onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG).performScrollToNode(hasTestTag("folder.delete"))
        composeRule.onNodeWithTag("folder.delete").assertIsEnabled().performClick()
        assertEquals(true, deleteRequested)
    }

    @Test
    fun deleteFailureShowsErrorAndKeepsRetryAvailable() {
        var retryRequested = false
        render(
            state = editState(name = "Work", isNew = false, error = app.getString(R.string.folder_delete_failed)),
            onDelete = { retryRequested = true },
        )
        composeRule.onNodeWithText(app.getString(R.string.folder_delete_failed)).assertExists()
        composeRule.onNodeWithTag(CHAT_FOLDER_EDIT_CONTENT_TAG).performScrollToNode(hasTestTag("folder.delete"))
        composeRule.onNodeWithTag("folder.delete").assertIsEnabled().performClick()
        assertEquals(true, retryRequested)
    }

    /** Edit state. */
    private fun editState(
        name: String,
        description: String = "",
        keyword: String = "",
        unreadOnly: Boolean = false,
        includeMuted: Boolean = false,
        groupsOnly: Boolean = false,
        archivedOnly: Boolean = false,
        manualChatCount: Int = 0,
        peopleCount: Int = 0,
        previewCount: Int = 0,
        isNew: Boolean = true,
        error: String? = null,
    ) = ChatFolderEditFormState(
        isNew = isNew,
        name = TextFieldState(name),
        description = TextFieldState(description),
        keyword = TextFieldState(keyword),
        unreadOnly = unreadOnly,
        includeMuted = includeMuted,
        groupsOnly = groupsOnly,
        archivedOnly = archivedOnly,
        manualChatCount = manualChatCount,
        peopleCount = peopleCount,
        previewCount = previewCount,
        canSave = name.isNotBlank(),
        error = error,
    )

    /** Composes the surface under test with the given fixture. */
    private fun render(
        state: ChatFolderEditFormState,
        onOpenManualChats: () -> Unit = {},
        onOpenPeople: () -> Unit = {},
        onOpenPreview: () -> Unit = {},
        onSave: () -> Unit = {},
        onDelete: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ChatFolderEditContent(
                        state = state,
                        onUnreadOnlyChange = {},
                        onIncludeMutedChange = {},
                        onGroupsOnlyChange = {},
                        onArchivedOnlyChange = {},
                        onOpenManualChats = onOpenManualChats,
                        onOpenPeople = onOpenPeople,
                        onOpenPreview = onOpenPreview,
                        onSave = onSave,
                        onDelete = onDelete,
                        onBack = onBack,
                    )
                }
            }
        }
    }
}
