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
            folders = listOf(folderRow(id = "work", name = "Work", isCustom = true)),
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
    fun systemFolderRowDoesNotExposeEditOrDeleteActions() {
        render(folders = listOf(folderRow(id = "unread", name = "Unread")))

        composeRule.onNodeWithContentDescription(app.getString(R.string.actions)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(app.getString(R.string.edit)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(app.getString(R.string.delete)).assertDoesNotExist()
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
        isCustom: Boolean = false,
        canMoveUp: Boolean = true,
        canMoveDown: Boolean = true,
    ) = ChatFolderManageItem(
        id = id,
        name = name,
        systemKind = if (isCustom) null else SystemFolderKind.UNREAD,
        chatCount = chatCount,
        isCustom = isCustom,
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
                    )
                }
            }
        }
    }
}
