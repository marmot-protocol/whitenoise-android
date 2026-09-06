package dev.ipf.whitenoise.android.ui.chats

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression for issue #1897: the active chat-list folder filter must survive
 * opening a conversation and returning; only explicit All / account switch clears it.
 */
class ChatListFolderFilterNavigationCoverageTest {
    @Test
    fun selectedFolderFilterOwnedByMainShellAcrossConversationRoute() {
        val mainShell = mainShellSource().readText()
        val chatsScreen = chatsScreenSource().readText()

        assertFalse(
            "ChatsScreen must not own folder filter state that dies when the conversation route replaces it",
            Regex("""var\s+selectedFolderId\s+by\s+remember""").containsMatchIn(chatsScreen),
        )

        assertTrue(
            "MainShell must remember the chat-list folder filter across conversation navigation",
            Regex("""var\s+selectedChatListFolderId\s+by\s+remember""").containsMatchIn(mainShell),
        )

        val chatsScreenWiring =
            mainShell.requiredSection(
                start = "ChatsScreen(",
                end = "\n                            )",
            )
        assertTrue(
            "ChatsScreen must receive the shell-owned folder filter",
            "selectedFolderId = selectedChatListFolderId" in chatsScreenWiring,
        )
        assertTrue(
            "folder chip selection must update shell-owned state",
            Regex("""onSelectFolder\s*=\s*\{[^}]*selectedChatListFolderId\s*=""").containsMatchIn(chatsScreenWiring),
        )

        val chatsScreenParams =
            chatsScreen.requiredSection(
                start = "internal fun ChatsScreen(",
                end = ") {",
            )
        assertTrue(
            "ChatsScreen must accept folder filter from its parent",
            "selectedFolderId:" in chatsScreenParams,
        )
        assertTrue(
            "ChatsScreen must expose a callback for folder filter changes",
            "onSelectFolder:" in chatsScreenParams,
        )
    }

    @Test
    fun conversationBackLeavesRememberedFolderFilterUnchanged() {
        val conversationRoute =
            mainShellSource().readText().requiredSection(
                start = "ConversationScreen(",
                end = "\n                    )",
            )
        val onBack =
            conversationRoute.requiredSection(
                start = "onBack = {",
                end = "\n                        },",
            )

        assertFalse(
            "returning from a conversation must not alter the remembered folder filter",
            "selectedChatListFolderId" in onBack,
        )
    }

    @Test
    fun zeroResultFolderRemainsSelectedAndRepresented() {
        val folderChipState =
            chatsScreenSource().readText().requiredSection(
                start = "val folderChipModels =",
                end = "\n\n    if (showNewChatFlow)",
            )

        assertTrue(
            "the selected folder must remain represented even when it has no current matches",
            "selectedFolderId = selectedFolderId" in folderChipState,
        )
        assertTrue(
            "only deleting the configured folder may implicitly clear its selection",
            "accountFolders.none { it.id == selectedFolderId }" in folderChipState,
        )
        assertFalse(
            "chip visibility must not implicitly clear the selected folder",
            "folderChipModels.none { it.folderId == selectedFolderId }" in folderChipState,
        )
    }

    /** Proves the permanent All chip remains the sole explicit folder-filter reset action. */
    @Test
    fun explicitAllActionClearsRememberedFolderFilter() {
        val filterChips =
            chatListTopBarSource().readText().requiredSection(
                start = "internal fun ChatListFilterChips(",
                end = "\n}",
            )

        assertTrue(
            "tapping All must explicitly clear the selected folder",
            "onClick = { onSelect(null) }" in filterChips,
        )
    }

    @Test
    fun accountSwitchClearsRememberedFolderFilter() {
        val accountResetBlock =
            mainShellSource().readText().requiredSection(
                // Anchored on the reset condition's tail: the reset is skipped
                // only while a notification-routed early open is landing its
                // own pinned account; every other account change still clears
                // the folder filter below.
                start = "&& !earlyOpenLandsPinnedAccount) {",
                end = "\n        }",
            )

        assertTrue(
            "account reset must clear the shell-owned folder filter so selections do not leak across accounts",
            "selectedChatListFolderId = null" in accountResetBlock,
        )
    }

    private fun chatsScreenSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing ChatsScreen.kt source file")

    private fun mainShellSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing MainShell.kt source file")

    private fun chatListTopBarSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatListTopBar.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatListTopBar.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing ChatListTopBar.kt source file")

    private fun String.requiredSection(
        start: String,
        end: String,
    ): String {
        val startIndex = indexOf(start)
        require(startIndex >= 0) { "Missing section start: $start" }
        val endIndex = indexOf(end, startIndex + start.length)
        require(endIndex >= 0) { "Missing section end: $end" }
        return substring(startIndex, endIndex)
    }
}
