package dev.ipf.whitenoise.android.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression for issue #1953: every production [NewGroupFlow] entry point must
 * route create submission, token-gated completion, and supersession through
 * shell-owned callbacks — not direct explicit conversation opens.
 */
class NewGroupFlowShellOwnershipCoverageTest {
    @Test
    fun chatListNewGroupFlowUsesShellOwnedCreateCallbacks() {
        val mainShell = mainShellSource().readText()
        val chatsScreen = chatsScreenSource().readText()

        val chatsScreenWiring =
            mainShell.requiredSection(
                start = "ChatsScreen(",
                end = "\n                        )",
            )
        assertTrue(
            "chat-list create submission must be shell-owned",
            "onGroupCreateSubmitted = onGroupCreateSubmitted" in chatsScreenWiring,
        )
        assertTrue(
            "chat-list create completion must be token-gated by the shell",
            "onGroupCreateCompletedOpen = openGroupFromGroupCreateCompletion" in chatsScreenWiring,
        )
        assertTrue(
            "chat-list create dismiss must supersede shell ownership",
            "onGroupCreateFlowSuperseded = supersedePendingGroupCreateOpen" in chatsScreenWiring,
        )

        val newChatFlowHost =
            chatsScreen.requiredSection(
                start = "NewChatFlowHost(",
                end = "\n        )",
            )
        assertTrue(
            "NewChatFlowHost must forward shell create submission",
            "onGroupCreateSubmitted = onGroupCreateSubmitted" in newChatFlowHost,
        )
        assertTrue(
            "NewChatFlowHost must forward shell token-gated completion",
            "onGroupCreateCompletedOpen(item, requestToken)" in newChatFlowHost,
        )

        val newGroupFlow =
            newChatFlowSource().readText().requiredSection(
                start = "NewChatStep.NewGroup ->",
                end = "\n            )",
            )
        assertTrue(
            "chat-list NewGroupFlow must wire onCreateSubmitted",
            "onCreateSubmitted = onGroupCreateSubmitted" in newGroupFlow,
        )
        assertTrue(
            "chat-list NewGroupFlow must wire token-gated completion",
            "onCreateCompletedOpen = onGroupCreateCompletedOpen" in newGroupFlow,
        )
        assertTrue(
            "chat-list NewGroupFlow must wire supersession",
            "onCreateFlowSuperseded = onGroupCreateFlowSuperseded" in newGroupFlow,
        )
    }

    @Test
    fun profileForegroundNewGroupFlowUsesShellOwnedCreateCallbacks() {
        val mainShell = mainShellSource().readText()
        val coordinator =
            mainShell.requiredSection(
                start = "    ProfileGroupForegroundCoordinator(",
                end = "\n    ) {",
            )

        assertTrue(
            "profile foreground must receive shell create submission",
            "onGroupCreateSubmitted = onGroupCreateSubmitted" in coordinator,
        )
        assertTrue(
            "profile foreground must receive token-gated completion",
            "onGroupCreateCompletedOpen = openGroupFromGroupCreateCompletion" in coordinator,
        )
        assertTrue(
            "profile foreground must receive supersession",
            "onGroupCreateFlowSuperseded = supersedePendingGroupCreateOpen" in coordinator,
        )

        val profileNewGroupFlow =
            mainShell.requiredSection(
                start = "is ProfileForegroundRoute.NewGroup -> {",
                end = "\n            return",
            )
        assertTrue(
            "profile NewGroupFlow must forward shell submission",
            "onCreateSubmitted = onGroupCreateSubmitted" in profileNewGroupFlow,
        )
        assertTrue(
            "profile NewGroupFlow must forward token-gated completion",
            "onGroupCreateCompletedOpen(item, requestToken)" in profileNewGroupFlow,
        )
        assertTrue(
            "profile NewGroupFlow dismiss must supersede ownership",
            "onGroupCreateFlowSuperseded()" in profileNewGroupFlow,
        )
    }

    @Test
    fun conversationScreenForwardsShellCreateCallbacksToGroupDetails() {
        val mainShell = mainShellSource().readText()
        val conversationScreen = conversationScreenSource().readText()

        val conversationWiring =
            mainShell.requiredSection(
                start = "ConversationScreen(",
                end = "\n                )",
            )
        assertTrue(
            "in-conversation create submission must be shell-owned",
            "onGroupCreateSubmitted = onGroupCreateSubmitted" in conversationWiring,
        )
        assertTrue(
            "in-conversation create completion must be token-gated by the shell",
            "onGroupCreateCompletedOpen = openGroupFromGroupCreateCompletion" in conversationWiring,
        )
        assertTrue(
            "in-conversation create dismiss must supersede shell ownership",
            "onGroupCreateFlowSuperseded = supersedePendingGroupCreateOpen" in conversationWiring,
        )

        val groupDetailsWiring =
            conversationScreen.requiredSection(
                start = "GroupDetailsScreen(",
                end = "\n        )",
            )
        assertTrue(
            "ConversationScreen must forward shell create submission to group details",
            "onGroupCreateSubmitted = onGroupCreateSubmitted" in groupDetailsWiring,
        )
        assertTrue(
            "ConversationScreen must forward token-gated completion to group details",
            "onGroupCreateCompletedOpen = onGroupCreateCompletedOpen" in groupDetailsWiring,
        )
        assertTrue(
            "ConversationScreen must forward supersession to group details",
            "onGroupCreateFlowSuperseded = onGroupCreateFlowSuperseded" in groupDetailsWiring,
        )
    }

    @Test
    fun groupDetailsStartGroupFlowUsesShellOwnedCreateCallbacks() {
        val groupDetailsScreen = groupDetailsScreenSource().readText()

        val startGroupFlow =
            groupDetailsScreen.requiredSection(
                start = "if (showStartGroupWithContact && dmPeerCandidate != null) {",
                end = "\n        return",
            )
        assertTrue(
            "group-details NewGroupFlow must wire onCreateSubmitted",
            "onCreateSubmitted = onGroupCreateSubmitted" in startGroupFlow,
        )
        assertTrue(
            "group-details NewGroupFlow must wire token-gated completion",
            "onCreateCompletedOpen = onGroupCreateCompletedOpen" in startGroupFlow,
        )
        assertTrue(
            "group-details NewGroupFlow must wire supersession",
            "onCreateFlowSuperseded = onGroupCreateFlowSuperseded" in startGroupFlow,
        )
        assertTrue(
            "group-details NewGroupFlow dismiss must supersede ownership",
            "onGroupCreateFlowSuperseded()" in startGroupFlow,
        )
        assertFalse(
            "group-details create completion must not bypass shell ownership",
            "onOpenConversation(item, false)" in startGroupFlow,
        )
    }

    @Test
    fun chatsScreenDefaultCompletionPreservesDirectOpenGroupBehavior() {
        val chatsScreenParams =
            chatsScreenSource().readText().requiredSection(
                start = "internal fun ChatsScreen(",
                end = ") {",
            )
        assertTrue(
            "direct ChatsScreen callers must still open created groups by default",
            Regex(
                """onGroupCreateCompletedOpen: \(ChatListItem, Long\) -> Unit = \{ item, _ ->
\s+onOpenGroup\(item, null, false, null\)
\s+\}""",
            ).containsMatchIn(chatsScreenParams),
        )
    }

    private fun mainShellSource(): File = source("ui/navigation/MainShell.kt")

    private fun chatsScreenSource(): File = source("ui/chats/ChatsScreen.kt")

    private fun conversationScreenSource(): File = source("ui/conversation/ConversationScreen.kt")

    private fun groupDetailsScreenSource(): File = source("ui/group/GroupDetailsScreen.kt")

    private fun newChatFlowSource(): File = source("ui/chats/newchat/NewChatFlow.kt")

    private fun source(relativePath: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relativePath"),
        ).firstOrNull { it.exists() }
            ?: error("Missing $relativePath source file")

    private fun String.requiredSection(
        start: String,
        end: String,
    ): String {
        val startIndex = indexOf(start)
        require(startIndex >= 0) { "Missing start marker: $start" }
        val endIndex = indexOf(end, startIndex + start.length)
        require(endIndex >= 0) { "Missing end marker: $end" }
        return substring(startIndex, endIndex + end.length)
    }
}
