package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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

/** Contract of the Folders list: rows edit on tap, their menu moves and deletes, and Restore follows the defaults. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h1200dp-mdpi")
class ChatFoldersContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Context>()

    /** The row is one button that edits, with Edit, the in-bounds move and Delete as accessibility actions. */
    @Test
    fun rowExposesEditMoveAndDeleteAccessibilityActionsRespectingListBounds() {
        var moved: Pair<String, Int>? = null
        var editedId: String? = null
        var deletedId: String? = null
        render(
            folders = listOf(folderRow(id = "unread", name = "Unread", canMoveUp = false, canMoveDown = true)),
            onMove = { id, delta -> moved = id to delta },
            onEdit = { editedId = it },
            onDelete = { deletedId = it },
        )
        val row =
            composeRule
                .onNode(
                    SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick) and
                        SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions),
                ).fetchSemanticsNode()
        val editAction = row.config[SemanticsActions.OnClick]
        val actions = row.config[SemanticsActions.CustomActions]
        assertEquals(Role.Button, row.config[SemanticsProperties.Role])
        assertEquals(app.getString(R.string.folder_edit), editAction.label)
        composeRule.runOnUiThread { editAction.action?.invoke() }
        assertEquals("unread", editedId)
        assertEquals(
            listOf(
                app.getString(R.string.folder_edit),
                app.getString(R.string.folder_move_down),
                app.getString(R.string.delete),
            ),
            actions.map { it.label },
        )
        composeRule.runOnUiThread { actions[1].action() }
        assertEquals("unread" to 1, moved)
        composeRule.runOnUiThread { actions[2].action() }
        assertEquals("unread", deletedId)
    }

    /** Tapping a default or custom folder name opens exactly that editor. */
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

    /** The actions menu of a custom folder offers Edit Folder, both moves and Delete. */
    @Test
    fun customFolderActionsMenuExposesEditMovesAndDelete() {
        var editedId: String? = null
        var deletedId: String? = null
        var moved: Pair<String, Int>? = null
        render(
            folders = listOf(folderRow(id = "work", name = "Work", systemKind = null)),
            onEdit = { editedId = it },
            onDelete = { deletedId = it },
            onMove = { id, delta -> moved = id to delta },
        )
        val menu = app.getString(R.string.actions_for, "Work")
        composeRule.onNodeWithContentDescription(menu).performClick()
        composeRule.onNodeWithText(app.getString(R.string.folder_edit)).performClick()
        assertEquals("work", editedId)
        composeRule.onNodeWithContentDescription(menu).performClick()
        composeRule.onNodeWithText(app.getString(R.string.folder_move_up)).performClick()
        assertEquals("work" to -1, moved)
        composeRule.onNodeWithContentDescription(menu).performClick()
        composeRule.onNodeWithText(app.getString(R.string.delete)).performClick()
        assertEquals("work", deletedId)
    }

    /** A default folder's menu offers the same Edit and Delete actions under its localized name. */
    @Test
    fun defaultFolderRowExposesTheSameEditAndDeleteActions() {
        var editedId: String? = null
        var deletedId: String? = null
        render(
            folders = listOf(folderRow(id = "unread", name = "Unread")),
            onEdit = { editedId = it },
            onDelete = { deletedId = it },
        )
        val menu = app.getString(R.string.actions_for, app.getString(R.string.chat_list_filter_unread))
        composeRule.onNodeWithContentDescription(menu).performClick()
        composeRule.onNodeWithText(app.getString(R.string.folder_edit)).performClick()
        assertEquals("unread", editedId)
        composeRule.onNodeWithContentDescription(menu).performClick()
        composeRule.onNodeWithText(app.getString(R.string.delete)).performClick()
        assertEquals("unread", deletedId)
    }

    /** A row shows its chat count and, when set, its description after a separator. */
    @Test
    fun rowShowsCountAndDescription() {
        render(folders = listOf(folderRow(id = "work", name = "Work", systemKind = null, description = "Team")))
        val count = app.resources.getQuantityString(R.plurals.chat_folder_chat_count, 2, 2)
        composeRule.onNodeWithText("$count · Team").assertExists()
    }

    /** Restore invokes its callback while a default is missing and is disabled when none is. */
    @Test
    fun restoreDefaultsFollowsMissingDefaults() {
        var restored = false
        render(
            folders = listOf(folderRow(id = "work", name = "Work", systemKind = null)),
            defaultsMissing = true,
            onRestoreDefaults = { restored = true },
        )
        composeRule
            .onNodeWithText(app.getString(R.string.folder_restore_defaults))
            .assertIsEnabled()
            .performClick()
        assertEquals(true, restored)
    }

    /** With every default present, Restore is disabled. */
    @Test
    fun restoreDefaultsIsDisabledWhenDefaultsArePresent() {
        render(folders = listOf(folderRow(id = "unread", name = "Unread")), defaultsMissing = false)
        composeRule.onNodeWithText(app.getString(R.string.folder_restore_defaults)).assertIsNotEnabled()
    }

    /** An empty list shows the empty state and still offers New folder in the top bar. */
    @Test
    fun emptyListShowsEmptyStateAndNewFolderAction() {
        var created = false
        render(folders = emptyList(), onCreate = { created = true })
        composeRule.onNodeWithText(app.getString(R.string.folder_none)).assertExists()
        composeRule.onNodeWithContentDescription(app.getString(R.string.folder_new_title)).performClick()
        assertEquals(true, created)
    }

    /** Native long-press opens the menu without also editing; choosing a move closes that menu. */
    @Test
    fun nativeLongPressDoesNotAlsoEditAndMoveClosesMenu() {
        var edited: String? = null
        var moved: Pair<String, Int>? = null
        render(
            folders = listOf(folderRow("work", "Work", systemKind = null)),
            onEdit = { edited = it },
            onMove = { id, delta -> moved = id to delta },
        )
        composeRule.onNodeWithTag("folder.row.work").performTouchInput { longClick() }
        assertEquals(null, edited)
        composeRule.onNodeWithText(app.getString(R.string.folder_move_down)).performClick()
        assertEquals("work" to 1, moved)
        composeRule.onNodeWithTag("folder.menu.work").assertDoesNotExist()
    }

    private fun folderRow(
        id: String,
        name: String,
        chatCount: Int = 2,
        systemKind: SystemFolderKind? = SystemFolderKind.UNREAD,
        canMoveUp: Boolean = true,
        canMoveDown: Boolean = true,
        description: String = "",
    ) = ChatFolderManageItem(
        id = id,
        name = name,
        systemKind = systemKind,
        chatCount = chatCount,
        canMoveUp = canMoveUp,
        canMoveDown = canMoveDown,
        description = description,
    )

    private fun render(
        folders: List<ChatFolderManageItem>,
        defaultsMissing: Boolean = true,
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
                        state = chatFoldersState(folders, defaultsMissing),
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
