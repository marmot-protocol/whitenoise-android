package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatFolderPreferences
import dev.ipf.whitenoise.android.state.SystemFolderKind
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ChatListFilterChipsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun shortTapSelectsFolder() {
        var selected: String? = "seed"
        var edited: String? = null
        render(
            chips =
                listOf(
                    chip(folderId = WORK_ID, label = "Work"),
                ),
            selectedFolderId = null,
            onSelect = { selected = it },
            onEditFolder = { edited = it },
        )

        composeRule.onNodeWithTag(chatListFilterChipTag(WORK_ID)).performClick()

        assertEquals(WORK_ID, selected)
        assertEquals(null, edited)
    }

    @Test
    fun longPressOpensEditorForExactFolderIdWithoutSelecting() {
        var selected: String? = "seed"
        var edited: String? = null
        render(
            chips =
                listOf(
                    chip(folderId = WORK_ID, label = "Work"),
                    chip(folderId = PERSONAL_ID, label = "Personal"),
                ),
            selectedFolderId = null,
            onSelect = { selected = it },
            onEditFolder = { edited = it },
        )

        composeRule.onNodeWithTag(chatListFilterChipTag(PERSONAL_ID)).performTouchInput { longClick() }

        assertEquals("seed", selected)
        assertEquals(PERSONAL_ID, edited)
    }

    @Test
    fun allChipHasNoLongPressEditAction() {
        var selected: String? = WORK_ID
        var edits = 0
        render(
            chips = listOf(chip(folderId = WORK_ID, label = "Work")),
            selectedFolderId = WORK_ID,
            onSelect = { selected = it },
            onEditFolder = { edits++ },
        )

        composeRule.onNodeWithTag(CHAT_LIST_FILTER_CHIP_ALL_TAG).performTouchInput { longClick() }

        assertEquals(WORK_ID, selected)
        assertEquals(0, edits)
        assertFalse(
            composeRule
                .onNodeWithTag(CHAT_LIST_FILTER_CHIP_ALL_TAG)
                .fetchSemanticsNode()
                .config
                .contains(SemanticsActions.OnLongClick),
        )
    }

    @Test
    fun renamedDefaultRoutesEditByStoredFolderId() {
        var edited: String? = null
        render(
            chips =
                listOf(
                    chip(
                        folderId = ChatFolderPreferences.SYSTEM_FOLDER_UNREAD_ID,
                        label = "Catch up",
                        systemKind = SystemFolderKind.UNREAD,
                    ),
                ),
            onEditFolder = { edited = it },
        )

        composeRule
            .onNodeWithText("Catch up", useUnmergedTree = true)
            .performTouchInput { longClick() }

        assertEquals(ChatFolderPreferences.SYSTEM_FOLDER_UNREAD_ID, edited)
    }

    @Test
    fun realFolderChipAccessibleActionCarriesRenderedLabel() {
        render(
            chips =
                listOf(
                    chip(folderId = WORK_ID, label = "Work"),
                    chip(
                        folderId = ChatFolderPreferences.SYSTEM_FOLDER_UNREAD_ID,
                        label = "Catch up",
                        systemKind = SystemFolderKind.UNREAD,
                    ),
                ),
        )

        assertFolderChipAccessibleLabel(WORK_ID, "Work")
        assertFolderChipAccessibleLabel(
            ChatFolderPreferences.SYSTEM_FOLDER_UNREAD_ID,
            "Catch up",
        )
        assertSingleActionableSemanticsSubtree(chatListFilterChipTag(WORK_ID))
        assertSingleActionableSemanticsSubtree(
            chatListFilterChipTag(ChatFolderPreferences.SYSTEM_FOLDER_UNREAD_ID),
        )
    }

    @Test
    fun realFolderChipExposesAccessibleLongClickAction() {
        var edited: String? = null
        render(
            chips = listOf(chip(folderId = WORK_ID, label = "Work")),
            onEditFolder = { edited = it },
        )

        val node =
            composeRule
                .onNodeWithTag(chatListFilterChipTag(WORK_ID))
                .fetchSemanticsNode()
        assertTrue(node.config.contains(SemanticsActions.OnLongClick))
        assertEquals(app.getString(R.string.edit), node.config[SemanticsActions.OnLongClick].label)

        composeRule
            .onNodeWithTag(chatListFilterChipTag(WORK_ID))
            .performSemanticsAction(SemanticsActions.OnLongClick)

        assertEquals(WORK_ID, edited)
    }

    @Test
    fun filterChipsExposeCheckboxRole() {
        render(
            chips =
                listOf(
                    chip(folderId = WORK_ID, label = "Work"),
                ),
            selectedFolderId = WORK_ID,
        )

        assertFilterChipRole(CHAT_LIST_FILTER_CHIP_ALL_TAG)
        assertFilterChipRole(chatListFilterChipTag(WORK_ID))
    }

    @Test
    fun folderChipIncludesLocalizedCountInAccessibleDescriptionWhenNonZero() {
        render(
            chips =
                listOf(
                    chip(folderId = WORK_ID, label = "Work", trailingCount = 3),
                ),
        )

        val expectedCount =
            app.resources.getQuantityString(R.plurals.chat_folder_chat_count, 3, 3)
        assertFolderChipAccessibleLabel(WORK_ID, "Work, $expectedCount")
    }

    @Test
    fun allChipExposesAccessibleShortTapThatClearsSelection() {
        var selected: String? = WORK_ID
        render(
            chips = listOf(chip(folderId = WORK_ID, label = "Work")),
            selectedFolderId = WORK_ID,
            onSelect = { selected = it },
        )

        composeRule
            .onNodeWithTag(CHAT_LIST_FILTER_CHIP_ALL_TAG)
            .performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(null, selected)
    }

    private fun chip(
        folderId: String,
        label: String,
        systemKind: SystemFolderKind? = null,
        trailingCount: Int = 0,
    ) = ChatFolderChipModel(
        folderId = folderId,
        systemKind = systemKind,
        customLabel = label,
        trailingCount = trailingCount,
    )

    private fun assertFilterChipRole(tag: String) {
        val role =
            composeRule
                .onNodeWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Role)
        assertEquals(Role.Checkbox, role)
    }

    private fun assertFolderChipAccessibleLabel(
        folderId: String,
        expectedLabel: String,
    ) {
        val node =
            composeRule
                .onNodeWithTag(chatListFilterChipTag(folderId), useUnmergedTree = true)
                .fetchSemanticsNode()
        val contentDescription =
            if (node.config.contains(SemanticsProperties.ContentDescription)) {
                node.config[SemanticsProperties.ContentDescription]
            } else {
                null
            }
        assertEquals(expectedLabel, contentDescription?.singleOrNull())
    }

    private fun assertSingleActionableSemanticsSubtree(tag: String) {
        val actionableCount =
            countActionableSemanticsNodes(
                composeRule
                    .onNodeWithTag(tag, useUnmergedTree = true)
                    .fetchSemanticsNode(),
            )
        assertEquals(1, actionableCount)
    }

    private fun countActionableSemanticsNodes(root: SemanticsNode): Int {
        var count = 0

        fun visit(node: SemanticsNode) {
            if (node.config.contains(SemanticsActions.OnClick) ||
                node.config.contains(SemanticsActions.OnLongClick)
            ) {
                count++
            }
            node.children.forEach(::visit)
        }
        visit(root)
        return count
    }

    private fun render(
        chips: List<ChatFolderChipModel>,
        selectedFolderId: String? = null,
        onSelect: (String?) -> Unit = {},
        onEditFolder: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ChatListFilterChips(
                        chips = chips,
                        selectedFolderId = selectedFolderId,
                        onSelect = onSelect,
                        onEditFolder = onEditFolder,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CHAT_LIST_FILTER_CHIP_ALL_TAG).assertIsDisplayed()
    }

    private companion object {
        const val WORK_ID = "folder-work"
        const val PERSONAL_ID = "folder-personal"
    }
}
