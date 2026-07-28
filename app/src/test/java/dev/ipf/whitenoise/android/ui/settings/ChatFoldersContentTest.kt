package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.SystemFolderKind
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ChatFoldersContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun reorderButtonsExposeContentDescriptionsAndRespectEnabledState() {
        render(
            folders =
                listOf(
                    folderRow(id = "unread", name = "Unread", canMoveUp = false, canMoveDown = true),
                ),
        )

        composeRule.onNodeWithContentDescription(app.getString(R.string.chat_folder_move_up)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(app.getString(R.string.chat_folder_move_down)).assertIsEnabled()
    }

    @Test
    fun customFolderActionsMenuExposesEditAndDelete() {
        var editedId: String? = null
        var deletedId: String? = null
        render(
            folders = listOf(folderRow(id = "work", name = "Work", systemKind = null)),
            onEdit = { editedId = it },
            onDelete = { deletedId = it },
        )

        composeRule.onNodeWithContentDescription(app.getString(R.string.actions)).performClick()
        composeRule.onNodeWithText(app.getString(R.string.edit)).performClick()
        assertEquals("work", editedId)

        composeRule.onNodeWithContentDescription(app.getString(R.string.actions)).performClick()
        composeRule.onNodeWithText(app.getString(R.string.delete)).performClick()
        assertEquals("work", deletedId)
    }

    @Test
    fun defaultFolderRowExposesTheSameEditAndDeleteActions() {
        var editedId: String? = null
        var deletedId: String? = null
        render(
            folders = listOf(folderRow(id = "unread", name = "Unread")),
            onEdit = { editedId = it },
            onDelete = { deletedId = it },
        )

        composeRule.onNodeWithContentDescription(app.getString(R.string.actions)).performClick()
        composeRule.onNodeWithText(app.getString(R.string.edit)).performClick()
        assertEquals("unread", editedId)

        composeRule.onNodeWithContentDescription(app.getString(R.string.actions)).performClick()
        composeRule.onNodeWithText(app.getString(R.string.delete)).performClick()
        assertEquals("unread", deletedId)
    }

    @Test
    fun restoreDefaultsRowInvokesTheCallback() {
        var restored = false
        render(
            folders = listOf(folderRow(id = "work", name = "Work", systemKind = null)),
            onRestoreDefaults = { restored = true },
        )

        composeRule.onNodeWithText(app.getString(R.string.chat_folder_restore_defaults)).performClick()
        assertEquals(true, restored)
    }

    @Test
    fun moveCallbacksReceiveFolderId() {
        var moved: Pair<String, Int>? = null
        render(
            folders =
                listOf(
                    folderRow(id = "unread", name = "Unread", canMoveUp = false, canMoveDown = true),
                ),
            onMove = { id, delta -> moved = id to delta },
        )

        composeRule.onNodeWithContentDescription(app.getString(R.string.chat_folder_move_down)).performClick()
        assertEquals("unread" to 1, moved)
    }

    private fun folderRow(
        id: String,
        name: String,
        chatCount: Int = 2,
        systemKind: SystemFolderKind? = SystemFolderKind.UNREAD,
        canMoveUp: Boolean = true,
        canMoveDown: Boolean = true,
    ) = ChatFolderManageItem(
        id = id,
        name = name,
        systemKind = systemKind,
        chatCount = chatCount,
        canMoveUp = canMoveUp,
        canMoveDown = canMoveDown,
    )

    private fun render(
        folders: List<ChatFolderManageItem>,
        onMove: (String, Int) -> Unit = { _, _ -> },
        onEdit: (String) -> Unit = {},
        onDelete: (String) -> Unit = {},
        onCreate: () -> Unit = {},
        onBack: () -> Unit = {},
        onRestoreDefaults: () -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ChatFoldersContent(
                        state = chatFoldersState(folders),
                        onBack = onBack,
                        onCreate = onCreate,
                        onMove = onMove,
                        onEdit = onEdit,
                        onDelete = onDelete,
                        onRestoreDefaults = onRestoreDefaults,
                    )
                }
            }
        }
    }
}
