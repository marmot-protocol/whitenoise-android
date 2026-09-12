package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ChatFolderPreferences
import dev.ipf.whitenoise.android.ui.navigation.rememberMainShellChatScope
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual pills and native rows exercise scope selection and canonical opening through the saved shell owner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatsLeftScopeFlowTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun leftFiltersNativeRowsAndOpensSameCanonicalHistoryWithoutActiveListHead() = render(hasLeft = true)

    @Test fun leftEmptyDoesNotClaimOrdinaryChatsAreEmpty() = render(hasLeft = false)

    @Test fun leftSearchCannotIncludeRowsFromOrdinaryChats() = render(hasLeft = true, search = true)

    @Test fun changingToLeftClearsSelectedMemberOutsideTheNewScope() = render(hasLeft = true, selectMember = true)

    @Test fun archivedFolderThenLeftThenChatsRetainsEachNativeSource() = render(hasLeft = true, archivedFirst = true)

    @Suppress("LongMethod")
    private fun render(
        hasLeft: Boolean,
        archivedFirst: Boolean = false,
        search: Boolean = false,
        selectMember: Boolean = false,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context
            .getSharedPreferences("whitenoise.chat_folders", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val app = ChatRowPortFixtures.state(context)
        val rows =
            listOf(leftScopeRow("member")) +
                if (hasLeft) listOf(leftScopeRow("left", SelfMembershipFfi.LEFT)) else emptyList()
        val controller = leftScopeController(app, rows + leftScopeRow("archived", archived = true))
        var folder by mutableStateOf(if (archivedFirst) ChatFolderPreferences.SYSTEM_FOLDER_ARCHIVED_ID else null)
        val opened = mutableListOf<Pair<String, String?>>()
        var searchState by mutableStateOf(GlobalSearchState(isOpen = search, query = if (search) "member" else ""))
        try {
            composeRule.setContent {
                val scope = rememberMainShellChatScope(app.activeAccountRef, app.runtimeGeneration)
                WhiteNoiseTheme {
                    ChatsScreen(
                        app,
                        controller,
                        {},
                        { item, _, _, head -> opened += item.id to head },
                        globalSearchState = searchState,
                        onGlobalSearchStateChange = { searchState = it(searchState) },
                        selectedFolderId = folder,
                        onSelectFolder = {
                            folder = it
                            scope.select(ChatScope.Chats)
                        },
                        chatScope = scope.scope,
                        onSelectScope = {
                            folder = null
                            scope.select(it)
                        },
                    )
                }
            }
            if (selectMember) {
                composeRule.onNodeWithTag("chat.row.member").performTouchInput { longClick() }
                composeRule.onNodeWithText(context.getString(R.string.select)).performClick()
                composeRule.onNodeWithTag("chat.row.member").assertIsSelected()
            }
            if (archivedFirst) {
                composeRule.onNodeWithTag("chat.row.archived").assertExists()
                composeRule.onNodeWithTag("chat.row.member").assertDoesNotExist()
            }
            composeRule.onNodeWithTag("chats.folders").performScrollToNode(hasTestTag("chats.scope.left"))
            composeRule.onNodeWithTag("chats.scope.left").performClick().assertIsSelected()
            composeRule.onNodeWithTag("chat.row.member").assertDoesNotExist()
            composeRule.onNodeWithTag("chat.row.archived").assertDoesNotExist()
            if (selectMember) {
                val count = context.resources.getQuantityString(R.plurals.chat_list_selected_count, 1, 1)
                composeRule.onNodeWithContentDescription(count).assertDoesNotExist()
            }
            if (search) {
                composeRule.onNodeWithTag("chat.row.left").assertDoesNotExist()
                composeRule.runOnIdle { searchState = searchState.copy(query = "left") }
            }
            if (hasLeft) {
                // The native placement gate intentionally rejects input until the filtered rows settle.
                composeRule.waitUntil(5_000) {
                    composeRule.mainClock.advanceTimeByFrame()
                    composeRule
                        .onAllNodes(hasTestTag("chat.row.left") and isEnabled())
                        .fetchSemanticsNodes()
                        .size == 1
                }
                composeRule.onNodeWithTag("chat.row.left").assertIsEnabled().performClick()
                assertEquals(listOf("left" to null), opened)
            } else {
                composeRule.onNodeWithTag("chats.empty.left").assertExists()
            }
            composeRule.onNodeWithTag("chats.folders").performScrollToNode(hasTestTag(CHAT_LIST_FILTER_CHIP_ALL_TAG))
            composeRule.onNodeWithTag(CHAT_LIST_FILTER_CHIP_ALL_TAG).performClick()
            if (search) {
                composeRule.onNodeWithTag("chat.row.member").assertDoesNotExist()
                composeRule.runOnIdle { searchState = searchState.copy(query = "") }
            }
            composeRule.onNodeWithTag("chat.row.member").assertExists()
            if (hasLeft) composeRule.onNodeWithTag("chat.row.left").assertExists()
        } finally {
            controller.onCleared()
            app.mutationsScope.cancel()
        }
    }
}
