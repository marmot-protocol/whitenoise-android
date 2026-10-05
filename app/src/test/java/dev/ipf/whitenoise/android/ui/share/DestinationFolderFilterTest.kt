package dev.ipf.whitenoise.android.ui.share

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.state.AccountSwitchLocalSnapshot
import dev.ipf.whitenoise.android.state.ChatFolder
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.messages.ForwardMessagePickerContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Both shipping pickers must keep browsing filters separate from explicit recipients. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class DestinationFolderFilterTest {
    @get:Rule val composeRule = createComposeRule()

    /** Folder/search changes preserve a hidden forward recipient and review reveals it before confirmation. */
    @Test
    fun forwardFiltersPreserveAndRevealHiddenSelections() = verifyFilterSelection(share = false)

    /** System sharing uses the same browsing semantics without staging when a filter is tapped. */
    @Test
    fun shareFiltersPreserveAndRevealHiddenSelections() = verifyFilterSelection(share = true)

    /** Forward folder membership, rename and deletion update in the mounted picker. */
    @Test
    fun forwardFolderUpdatesAndDeletionAreLive() = verifyFolderUpdates(share = false)

    /** System-share folder membership, rename and deletion update without reopening the picker. */
    @Test
    fun shareFolderUpdatesAndDeletionAreLive() = verifyFolderUpdates(share = true)

    /** Accounts with no saved folders do not gain a redundant browsing row in either picker. */
    @Test
    fun noSavedFoldersLeaveShareDestinationsUsable() {
        val state = state()
        val store = state.chatFolderPreferences
        store.foldersFor(ACCOUNT_REF).forEach { store.deleteFolder(ACCOUNT_REF, it.id) }
        render(state, share = true)
        composeRule.onNodeWithTag("destination.filter.all").assertDoesNotExist()
        composeRule
            .onNodeWithText("Alice")
            .assertIsDisplayed()
            .performClick()
            .assertIsSelected()
    }

    /** Checks search intersection, explicit selection and the all-selected review route on the real surface. */
    private fun verifyFilterSelection(share: Boolean) {
        val state = state()
        val folder = folder(state, setOf(GROUP_B))
        var committed = emptyList<String>()
        render(state, share) { committed = it }
        composeRule.onNodeWithText("Alice").performClick()
        chooseFolder(folder)
        composeRule.onNodeWithText("Alice").assertDoesNotExist()
        composeRule.onNodeWithText("Bob").assertIsDisplayed()
        assertEquals(emptyList<String>(), committed)
        composeRule.onNode(hasSetTextAction()).performTextInput("Alice")
        composeRule.onNodeWithText("Bob").assertDoesNotExist()
        composeRule.onNode(hasSetTextAction()).performTextClearance()
        composeRule.onNodeWithTag("destination.filter.${folder.id}").assertIsSelected()
        composeRule.onNodeWithText("Bob").assertIsDisplayed()
        composeRule
            .onNodeWithTag("destination.filters")
            .performScrollToNode(hasTestTag("destination.filter.selected"))
        composeRule.onNodeWithTag("destination.filter.selected").performClick()
        composeRule.onNodeWithText("Alice").assertIsSelected()
        composeRule.onNodeWithText("Bob").assertDoesNotExist()
        composeRule.onNodeWithText(if (share) "Share to 1 chat" else "Forward to 1 chat").performClick()
        composeRule.waitUntil(5_000) { committed.isNotEmpty() }
        assertEquals(listOf(GROUP_A), committed)
    }

    /** Empty folders retain the All escape hatch; live preferences never mutate recipient selection. */
    private fun verifyFolderUpdates(share: Boolean) {
        val state = state()
        val folder = folder(state, emptySet())
        render(state, share)
        chooseFolder(folder)
        composeRule.onNodeWithText("Alice").assertDoesNotExist()
        composeRule.onNodeWithText("Bob").assertDoesNotExist()
        composeRule.onNodeWithTag("destination.filter.all").assertIsDisplayed()
        composeRule.onNodeWithText("No matching chats are loaded yet. Retry or choose All chats.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        composeRule.runOnIdle {
            state.chatFolderPreferences.setChatInFolder(ACCOUNT_REF, folder.id, GROUP_A, true)
            state.chatFolderPreferences.renameFolder(ACCOUNT_REF, folder.id, "Renamed")
        }
        composeRule.onNodeWithText("Alice").assertIsDisplayed()
        composeRule.onNodeWithText("Bob").assertDoesNotExist()
        composeRule.runOnIdle { state.chatFolderPreferences.deleteFolder(ACCOUNT_REF, folder.id) }
        composeRule.onNodeWithTag("destination.filter.all").assertIsSelected()
        composeRule.onNodeWithText("Alice").assertIsDisplayed()
        composeRule.onNodeWithText("Bob").assertIsDisplayed()
    }

    /** Scrolls only the compact filter row, leaving recipient list scrolling independent. */
    private fun chooseFolder(folder: ChatFolder) {
        val tag = "destination.filter.${folder.id}"
        composeRule.onNodeWithTag("destination.filters").performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag).performClick()
    }

    /** Builds two eligible synthetic destinations with no native/network work. */
    private fun state(): WhiteNoiseAppState {
        val state = emptyAppState(profiles = mutableMapOf(PEER_A to profile("Alice"), PEER_B to profile("Bob")))
        val snapshot =
            AccountSwitchLocalSnapshot(
                ACCOUNT_REF,
                ACCOUNT_HEX,
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
            )
        val controller =
            ChatsController(
                appState = state,
                initialAccountRef = ACCOUNT_REF,
                memberSnapshotLoader = { _, _ -> emptyList() },
                initialLocalSnapshot = snapshot,
            )
        controller.applyLocalDirectChat(GROUP_A, ACCOUNT_HEX, PEER_A)
        controller.applyLocalDirectChat(GROUP_B, ACCOUNT_HEX, PEER_B)
        state.attachChatsController(controller)
        return state
    }

    /** Persists the account-local folder using the same preference transaction as the editor. */
    private fun folder(
        state: WhiteNoiseAppState,
        members: Set<String>,
    ): ChatFolder {
        val store = state.chatFolderPreferences
        store.clearAllForAccount(ACCOUNT_REF)
        store.foldersFor(ACCOUNT_REF)
        return requireNotNull(store.commitFolderDraft(ACCOUNT_REF, null, "Work", "", members, null))
    }

    /** Mounts the actual forward/share content and records only explicit confirmation. */
    private fun render(
        state: WhiteNoiseAppState,
        share: Boolean,
        onCommit: (List<String>) -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                if (share) {
                    ShareChatPickerFullScreenContent(
                        appState = state,
                        payload = SharePayload("Fixture", emptyList(), "text/plain"),
                        onDismiss = {},
                        onStage = { _, ids ->
                            onCommit(ids)
                            true
                        },
                    )
                } else {
                    ForwardMessagePickerContent(
                        appState = state,
                        messageCount = 1,
                        attachmentCount = 0,
                        originGroupIdHex = "ff".repeat(32),
                        sourceAccountRef = ACCOUNT_REF,
                        onDismiss = {},
                        onForward = { _, ids ->
                            onCommit(ids)
                            true
                        },
                    )
                }
            }
        }
    }
}
