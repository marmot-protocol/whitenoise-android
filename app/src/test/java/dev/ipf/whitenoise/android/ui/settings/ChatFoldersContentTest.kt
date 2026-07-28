package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
    fun rowExposesAccessibilityMoveActionsRespectingListBounds() {
        var moved: Pair<String, Int>? = null
        render(
            folders =
                listOf(
                    folderRow(id = "unread", name = "Unread", canMoveUp = false, canMoveDown = true),
                ),
            onMove = { id, delta -> moved = id to delta },
        )

        // The drag gesture's TalkBack fallback: only the in-bounds direction
        // is offered, and invoking it moves the row.
        val actions =
            composeRule
                .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions), useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
        assertEquals(listOf(app.getString(R.string.chat_folder_move_down)), actions.map { it.label })

        composeRule.runOnUiThread { actions.single().action() }
        assertEquals("unread" to 1, moved)
    }

    @Test
    fun dragHandleIsExposedOnEveryRow() {
        render(
            folders =
                listOf(
                    folderRow(id = "unread", name = "Unread"),
                    folderRow(id = "work", name = "Work", systemKind = null),
                ),
        )

        composeRule
            .onAllNodesWithContentDescription(app.getString(R.string.chat_folder_drag_to_reorder))
            .assertCountEquals(2)
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
