package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.material3.Surface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
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
    fun rowExposesAccessibilityEditAndMoveActionsRespectingListBounds() {
        var moved: Pair<String, Int>? = null
        var editedId: String? = null
        render(
            folders =
                listOf(
                    folderRow(id = "unread", name = "Unread", canMoveUp = false, canMoveDown = true),
                ),
            onMove = { id, delta -> moved = id to delta },
            onEdit = { editedId = it },
        )

        val row =
            composeRule
                .onNode(
                    SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick) and
                        SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions),
                ).fetchSemanticsNode()
        val editAction = row.config[SemanticsActions.OnClick]
        val moveActions = row.config[SemanticsActions.CustomActions]

        assertEquals(app.getString(R.string.edit), editAction.label)
        composeRule.runOnUiThread { editAction.action?.invoke() }
        assertEquals("unread", editedId)

        // The drag gesture's TalkBack fallback still offers only the
        // in-bounds direction on the same editable row.
        assertEquals(listOf(app.getString(R.string.chat_folder_move_down)), moveActions.map { it.label })
        composeRule.runOnUiThread { moveActions.single().action() }
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
    fun draggingHandleReordersWithoutOpeningEditor() {
        var moved: Pair<String, Int>? = null
        var editedId: String? = null
        render(
            folders =
                listOf(
                    folderRow(id = "unread", name = "Unread", canMoveUp = false, canMoveDown = true),
                    folderRow(id = "work", name = "Work", systemKind = null, canMoveDown = false),
                ),
            onMove = { id, delta -> moved = id to delta },
            onEdit = { editedId = it },
        )

        composeRule
            .onAllNodesWithContentDescription(
                app.getString(R.string.chat_folder_drag_to_reorder),
                useUnmergedTree = true,
            )[0]
            .performTouchInput {
                down(center)
                moveBy(Offset(0f, 20f))
                moveBy(Offset(0f, 200f))
                up()
            }

        assertEquals("unread" to 1, moved)
        assertEquals(null, editedId)
    }

    @Test
    fun customAndDefaultFolderNamesOpenTheirExactEditors() {
        val editedIds = mutableListOf<String>()
        render(
            folders =
                listOf(
                    folderRow(id = "unread", name = "Unread"),
                    folderRow(id = "work", name = "Work", systemKind = null),
                ),
            onEdit = editedIds::add,
        )

        composeRule.onNodeWithText(app.getString(R.string.chat_list_filter_unread)).performClick()
        composeRule.onNodeWithText("Work").performClick()

        assertEquals(listOf("unread", "work"), editedIds)
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
