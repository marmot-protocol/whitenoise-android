package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.audio.kotlinFunctionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression for issue #1313: opening a conversation from the shell-level
 * profile sheet after presenting it from the chat list must carry the same
 * visible filtered active-list head snapshot used by direct row opens.
 */
class ChatListProfileReturnSnapCoverageTest {
    @Test
    fun chatListProfileOpenCapturesVisibleFilteredHeadForReturnSnap() {
        val chatsScreen = chatsScreenSource().readText()
        val mainShell = mainShellSource().readText()

        val presentProfileBody = chatsScreen.kotlinFunctionBody("presentProfileFromVisibleList")
        assertTrue(
            "chat-list profile capture must use the filtered visible list head",
            "visibleItems.firstOrNull()?.id" in presentProfileBody,
        )
        assertFalse(
            "profile capture must not substitute the unfiltered controller head",
            "controller.items.firstOrNull()?.id" in presentProfileBody,
        )

        val openGroupFromProfileBlock =
            mainShell.requiredSection(
                start = "val openGroupFromProfile:",
                end = "\n\n    val conversationControllerCopy",
            )
        assertTrue(
            "profile conversation open must transfer or clear return-head provenance via reducer",
            Regex("""openGroupFromProfileSheet\(""").containsMatchIn(openGroupFromProfileBlock),
        )

        val onPresentProfileBlock =
            mainShell.requiredSection(
                start = "onPresentProfile = {",
                end = "MainSection.Settings ->",
            )
        assertTrue(
            "chat-list profile presentation must arm the list-bound snapshot",
            Regex("""presentProfileFromChatList\(""").containsMatchIn(onPresentProfileBlock),
        )
        assertTrue(
            "visible filtered head must be forwarded into profile presentation",
            "visibleHeadId" in onPresentProfileBlock,
        )
    }

    @Test
    fun chatListRowAvatarUsesProfileCapturePath() {
        val chatsScreen = chatsScreenSource().readText()
        val chatRow = chatRowSource().readText()

        assertTrue(
            "ChatListRow must delegate avatar profile opens to the caller",
            "onOpenProfile" in chatRow,
        )
        assertFalse(
            "ChatRow must not call presentProfile directly for avatar taps",
            Regex("""appState\.presentProfile\(""").containsMatchIn(chatRow),
        )
        assertTrue(
            "ChatsScreen must wire avatar profile opens through visible-head capture",
            "onOpenProfile" in chatsScreen && "presentProfileFromVisibleList" in chatsScreen,
        )
    }

    @Test
    fun profileSnapshotClearsOnDismissAndUnrelatedNavigation() {
        val mainShell = mainShellSource().readText()
        val profileSheetBlock =
            mainShell.requiredSection(
                start = "ProfileGroupForegroundCoordinator(",
                end = "val routeForwardDirection =",
                occurrence = 1,
            )

        val onDismissBlock =
            profileSheetBlock.requiredSection(
                start = "onDismissProfile = {",
                end = "onClosePicker =",
            )
        assertTrue(
            "profile dismiss must clear the list-bound snapshot",
            Regex("""dismissChatListProfile\(""").containsMatchIn(onDismissBlock),
        )

        val onOpenSettingsBlock =
            mainShell.requiredSection(
                start = "onOpenSettings = {",
                end = "onOpenGroup = {",
            )
        assertTrue(
            "unrelated chat-list navigation must reset return-head provenance",
            Regex("""resetChatListReturnHeadSnap\(""").containsMatchIn(onOpenSettingsBlock),
        )

        val onOpenGroupBlock =
            mainShell.requiredSection(
                start = "onOpenGroup = {",
                end = "onPresentProfile = {",
            )
        assertTrue(
            "direct list opens must carry visible-head provenance into the prepared-route request",
            "PendingConversationOpen(" in onOpenGroupBlock &&
                "visibleActiveListHeadId = visibleHeadId" in onOpenGroupBlock,
        )
    }

    @Test
    fun nonListConversationOpensDoNotConsumeStaleReturnHead() {
        val mainShell = mainShellSource().readText()
        val stateHolder = mainShellStateHolderSource().readText()

        val notificationCommitBlock =
            mainShell.requiredSection(
                start = "fun commitNotificationConversationOpen(chatItem: ChatListItem) {",
                end = "\n        fun fallBackToChatList() {",
            )
        assertTrue(
            "shared notification-open commit must reset armed return-head provenance",
            Regex("""resetChatListReturnHeadSnap\(""").containsMatchIn(notificationCommitBlock),
        )

        val notificationOpenBlock =
            mainShell.requiredSection(
                start = "is NotificationNavStep.OpenConversation -> {",
                end = "\n            NotificationNavStep.MissingAccount -> {",
            )
        assertTrue(
            "notification opens must use the shared state commit",
            Regex("""commitNotificationConversationOpen\(""").containsMatchIn(notificationOpenBlock),
        )

        assertTrue(
            "return-head provenance must remain composition-local and start unarmed",
            "var chatListReturnHeadSnap by remember" in mainShell &&
                "ChatListReturnHeadSnapState.Unarmed" in mainShell,
        )
        assertFalse(
            "process-restored conversation routes must not retain stale return-head provenance",
            "chatListReturnHeadSnap" in stateHolder,
        )
        assertTrue(
            "process-restored conversation routes must be resolved by the retained shell state holder",
            "restoreConversationIfReady" in stateHolder,
        )
    }

    @Test
    fun returnHeadPublishWiringUsesSingleStateMachine() {
        val mainShell = mainShellSource().readText()

        assertFalse(
            "published return head must not live in a separate pending nullable",
            "pendingConversationReturnHeadId" in mainShell,
        )

        val visibilityEffect =
            mainShell.requiredSection(
                start = "LaunchedEffect(chatsController, selectedChat == null) {",
                end = "\n    }\n\n    // Notification tap routing",
            )
        val setVisibleIndex = visibilityEffect.indexOf("setChatListVisible")
        val publishIndex = visibilityEffect.indexOf("onChatListBecameVisible")
        assertTrue("chat list visibility must be set before publishing return head", setVisibleIndex >= 0)
        assertTrue("return head must publish after list becomes visible", publishIndex >= 0)
        assertTrue(
            "setChatListVisible(true) must run before onChatListBecameVisible",
            setVisibleIndex < publishIndex,
        )

        val chatsScreenWiring =
            mainShell.requiredSection(
                start = "ChatsScreen(",
                end = "MainSection.Settings ->",
            )
        assertTrue(
            "ChatsScreen must receive the published head from the state machine",
            Regex("""publishedConversationReturnHead\(""").containsMatchIn(chatsScreenWiring),
        )
        assertTrue(
            "return-head consumption must clear Published via reducer",
            Regex("""onConversationReturnHeadHandled\(chatListReturnHeadSnap\)""").containsMatchIn(chatsScreenWiring),
        )

        val accountResetBlock =
            mainShell.requiredSection(
                start = "&& !earlyOpenLandsPinnedAccount) {",
                end = "\n        }",
            )
        assertTrue(
            "account reset must clear return-head provenance",
            Regex("""resetChatListReturnHeadSnap\(""").containsMatchIn(accountResetBlock),
        )
    }

    @Test
    fun accountSwitchDismissesSharePickerBeforeTargetsCanCrossAccounts() {
        val accountResetBlock =
            mainShellSource().readText().requiredSection(
                start = "&& !earlyOpenLandsPinnedAccount) {",
                end = "\n        }",
            )

        assertTrue(
            "account reset must dismiss the picker holding the previous account's group ids",
            "clearSharePickerRequest()" in accountResetBlock,
        )
    }

    private fun chatsScreenSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing ChatsScreen.kt source file")

    private fun chatRowSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatRow.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatRow.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing ChatRow.kt source file")

    private fun mainShellSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing MainShell.kt source file")

    private fun mainShellStateHolderSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShellStateHolder.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShellStateHolder.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing MainShellStateHolder.kt source file")

    private fun String.requiredSection(
        start: String,
        end: String,
        occurrence: Int = 0,
    ): String {
        var searchFrom = 0
        repeat(occurrence + 1) {
            val startIndex = indexOf(start, searchFrom)
            require(startIndex >= 0) { "Missing section start: $start" }
            if (it == occurrence) {
                val endIndex = indexOf(end, startIndex + start.length)
                require(endIndex >= 0) { "Missing section end: $end" }
                return substring(startIndex, endIndex)
            }
            searchFrom = startIndex + start.length
        }
        error("unreachable")
    }
}
