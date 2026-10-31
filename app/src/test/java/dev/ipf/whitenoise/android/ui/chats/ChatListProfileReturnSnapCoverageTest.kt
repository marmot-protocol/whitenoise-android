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
    /** Profile opens from the list capture the same filtered, pinned-aware head as direct row opens. */
    @Test
    fun chatListProfileOpenCapturesVisibleFilteredHeadForReturnSnap() {
        val chatsScreen = chatsScreenSource().readText()
        val mainShell = mainShellSource().readText()

        val presentProfileBody = chatsScreen.kotlinFunctionBody("presentProfileFromVisibleList")
        assertTrue(
            "chat-list profile capture must use the filtered visible list head",
            "chatListReturnHead(visibleItems)" in presentProfileBody,
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
            "presentProfileFromChatList(chatListReturnHeadSnap, visibleHead)" in onPresentProfileBlock,
        )
    }

    /**
     * Every list-origin open captures the pinned head and the first unpinned row, and the return
     * effect compares both, so a send promoting a chat beneath unchanged pins still snaps (#3280).
     */
    @Test
    fun listOpensCaptureAndReturnComparesThePinnedAwareHead() {
        val chatsScreen = chatsScreenSource().readText()

        val headBody = chatsScreen.kotlinFunctionBody("visibleReturnHead")
        assertTrue(
            "list opens must capture the pinned head and first unpinned row together",
            "chatListReturnHead(visibleItems)" in headBody,
        )
        assertFalse(
            "list opens must not fall back to the absolute first row alone",
            "visibleItems.firstOrNull()?.id" in headBody,
        )
        assertTrue(
            "direct list opens must capture through the shared visible head",
            "visibleReturnHead()" in chatsScreen.kotlinFunctionBody("openGroupFromVisibleList"),
        )

        val returnEffect =
            chatsScreen.requiredSection(
                start = "LaunchedEffect(conversationReturnHead,",
                end = "onConversationReturnHeadHandled()",
            )
        assertTrue(
            "the return effect must compare the captured head with the current pinned-aware head",
            "currentHead = activeReturnHead" in returnEffect &&
                "headAtConversationOpen = headAtConversationOpen" in returnEffect,
        )
        assertTrue(
            "the current head must be derived from the same filtered visible rows as the capture",
            "val activeReturnHead = chatListReturnHead(visibleItems).takeIf { ordinaryActiveList }" in chatsScreen,
        )
        assertTrue(
            "a snap must reach the absolute top above the pinned block",
            "chatListState.scrollToItem(0)" in returnEffect,
        )
    }

    /**
     * The list is not composed behind New Message, so its keyed scroll anchor keeps the head it
     * showed when the flow opened. Chats created there return against that head, not one read at
     * completion that may already include the new chat (#3529).
     */
    @Test
    fun newMessageFlowOpensReturnAgainstTheHeadHeldWhenTheFlowOpened() {
        val chatsScreen = chatsScreenSource().readText()
        val mainShell = mainShellSource().readText()

        val openFlow =
            chatsScreen.requiredSection(
                start = "val openNewMessageFlow = {",
                end = "showNewChatFlow = true",
            )
        assertTrue(
            "opening New Message must capture the visible head first",
            "newChatFlowOriginHead = visibleReturnHead()" in openFlow,
        )

        val flowHost =
            chatsScreen.requiredSection(
                start = "NewChatFlowHost(",
                end = "\n        return\n",
            )
        assertTrue(
            "a created or reopened direct chat must return against the flow-origin head",
            "onOpenGroup(item, null, justCreated, newChatFlowReturnHead())" in flowHost,
        )
        assertTrue(
            "a created group must return against the flow-origin head",
            "onGroupCreateCompletedOpen(item, requestToken, newChatFlowReturnHead())" in flowHost,
        )

        assertTrue(
            "closing New Message must restore a head that moved behind it",
            "onClose = ::closeNewChatFlow" in flowHost &&
                "onSourceLeave = ::closeNewChatFlow" in chatsScreen &&
                "chatListState.requestScrollToItem(0)" in chatsScreen.kotlinFunctionBody("closeNewChatFlow"),
        )

        val createdGroupOpen =
            mainShell.requiredSection(
                start = "val openGroupFromGroupCreateCompletion:",
                end = "selectedChat = item",
            )
        assertTrue(
            "the shell must arm a created group from the flow-origin head",
            "openCreatedGroup(chatListReturnHeadSnap, flowOriginHead)" in createdGroupOpen,
        )
    }

    /** Row avatar taps reach the profile through the head-capturing path. */
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

    /** Dismissal and unrelated navigation clear the captured profile head. */
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
                "visibleActiveListHead = visibleHead" in onOpenGroupBlock,
        )
    }

    /** Notification and other non-list opens cannot consume a list-return head retained by an earlier route. */
    @Test
    fun nonListConversationOpensDoNotConsumeStaleReturnHead() {
        val mainShell = mainShellSource().readText()
        val stateHolder = mainShellStateHolderSource().readText()

        val notificationCommitBlock =
            mainShell.requiredSection(
                start = "override fun openRoutedConversation(",
                end = "override fun targetHandled(",
            )
        assertTrue(
            "shared notification-open commit must reset armed return-head provenance",
            Regex("""resetChatListReturnHeadSnap\(""").containsMatchIn(notificationCommitBlock),
        )

        val router = notificationOpenRouterSource().readText()
        val notificationOpenBlock =
            router.requiredSection(
                start = "is NotificationNavStep.OpenConversation ->",
                end = "\n            NotificationNavStep.MissingAccount ->",
            )
        val openListed = router.requiredSection(start = "suspend fun openListed(", end = "suspend fun commit(")
        val commit = router.requiredSection(start = "suspend fun commit(", end = "private fun ")
        assertTrue(
            "notification opens must use the shared state commit",
            Regex("""completion\.openListed\(""").containsMatchIn(notificationOpenBlock) &&
                Regex("""\n\s+commit\(attempt, item\)""").containsMatchIn(openListed) &&
                "shell.openRoutedConversation(" in commit,
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

    /** The published return head lives only in the shell's single return state machine, not a separate nullable. */
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
                end = "\n    }\n\n    LaunchedEffect(appState.pendingProfileNpub) {",
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

    /** The notification-open router that owns the routed open's commit (#3309). */
    private fun notificationOpenRouterSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/navigation/NotificationOpenRouter.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/navigation/NotificationOpenRouter.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing NotificationOpenRouter.kt source file")

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
