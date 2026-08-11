package dev.ipf.whitenoise.android.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellNavigationTest {
    /**
     * Mirrors MainShell: explicit opens always commit; create completion commits
     * only when [ShellNavigationTransition.createOpenAccepted] is true.
     */
    private fun applyShellSelection(
        selectedChat: String?,
        event: ShellNavigationEvent,
        transition: ShellNavigationTransition,
    ): String? =
        when (event) {
            is ShellNavigationEvent.ExplicitConversationOpened -> event.chatId
            is ShellNavigationEvent.CreateCompleted ->
                if (transition.createOpenAccepted) event.chatId else selectedChat
            ShellNavigationEvent.ConversationBackedOut,
            ShellNavigationEvent.AccountSwitched,
            -> null
            else -> selectedChat
        }

    private fun reduceAndApply(
        state: ShellNavigationState,
        selectedChat: String?,
        event: ShellNavigationEvent,
    ): Pair<ShellNavigationState, String?> {
        val transition = reduceShellNavigation(state, event)
        return transition.state to applyShellSelection(selectedChat, event, transition)
    }

    private fun completeCreate(
        state: ShellNavigationState,
        selectedChat: String?,
        groupId: String,
        token: Long,
    ): Triple<ShellNavigationState, String?, ShellNavigationTransition> {
        val event = ShellNavigationEvent.CreateCompleted(groupId, token)
        val transition = reduceShellNavigation(state, event)
        return Triple(
            transition.state,
            applyShellSelection(selectedChat, event, transition),
            transition,
        )
    }

    @Test
    fun createSubmittedThenNotificationOpenedThenCreateCompleted_keepsNotificationChat() {
        val groupA = "group-a"
        val groupB = "group-b"

        var state = ShellNavigationState()
        var selectedChat: String? = null
        val submitA = reduceShellNavigation(state, ShellNavigationEvent.CreateSubmitted)
        state = submitA.state
        val (afterOpenState, afterOpen) =
            reduceAndApply(
                state,
                selectedChat,
                ShellNavigationEvent.ExplicitConversationOpened(groupB),
            )
        state = afterOpenState
        selectedChat = afterOpen
        val (_, _, completeA) =
            completeCreate(
                state,
                selectedChat,
                groupA,
                submitA.createRequestTokenMinted!!,
            )

        assertFalse(completeA.createOpenAccepted)
        assertEquals(groupB, selectedChat)
    }

    @Test
    fun uninterruptedCreateCompletion_opensCreatedGroup() {
        val groupA = "group-a"

        var state = ShellNavigationState()
        var selectedChat: String? = null
        val submit = reduceShellNavigation(state, ShellNavigationEvent.CreateSubmitted)
        state = submit.state
        val (_, afterComplete, complete) =
            completeCreate(
                state,
                selectedChat,
                groupA,
                submit.createRequestTokenMinted!!,
            )
        selectedChat = afterComplete

        assertTrue(complete.createOpenAccepted)
        assertEquals(groupA, selectedChat)
    }

    @Test
    fun createFlowDismissedBeforeCompletion_doesNotOpenCreatedGroup() {
        val groupA = "group-a"

        var state = ShellNavigationState()
        var selectedChat: String? = null
        val submit = reduceShellNavigation(state, ShellNavigationEvent.CreateSubmitted)
        state = submit.state
        state = reduceShellNavigation(state, ShellNavigationEvent.CreateFlowSuperseded).state
        val (_, afterComplete, complete) =
            completeCreate(
                state,
                selectedChat,
                groupA,
                submit.createRequestTokenMinted!!,
            )
        selectedChat = afterComplete

        assertFalse(complete.createOpenAccepted)
        assertNull(selectedChat)
    }

    @Test
    fun accountSwitchBeforeCompletion_doesNotOpenCreatedGroup() {
        val groupA = "group-a"

        var state = ShellNavigationState()
        var selectedChat: String? = null
        val submit = reduceShellNavigation(state, ShellNavigationEvent.CreateSubmitted)
        state = submit.state
        val (afterSwitchState, afterSwitch) =
            reduceAndApply(state, selectedChat, ShellNavigationEvent.AccountSwitched)
        state = afterSwitchState
        selectedChat = afterSwitch
        val (_, afterComplete, complete) =
            completeCreate(
                state,
                selectedChat,
                groupA,
                submit.createRequestTokenMinted!!,
            )
        selectedChat = afterComplete

        assertFalse(complete.createOpenAccepted)
        assertNull(selectedChat)
    }

    @Test
    fun conversationBackBeforeCompletion_doesNotReopenCreatedGroup() {
        val groupA = "group-a"
        val groupB = "group-b"

        var state = ShellNavigationState()
        var selectedChat: String? = null
        val submit = reduceShellNavigation(state, ShellNavigationEvent.CreateSubmitted)
        state = submit.state
        val (afterOpenState, afterOpen) =
            reduceAndApply(
                state,
                selectedChat,
                ShellNavigationEvent.ExplicitConversationOpened(groupB),
            )
        state = afterOpenState
        selectedChat = afterOpen
        val (afterBackState, afterBack) =
            reduceAndApply(state, selectedChat, ShellNavigationEvent.ConversationBackedOut)
        state = afterBackState
        selectedChat = afterBack
        val (_, afterComplete, complete) =
            completeCreate(
                state,
                selectedChat,
                groupA,
                submit.createRequestTokenMinted!!,
            )
        selectedChat = afterComplete

        assertFalse(complete.createOpenAccepted)
        assertNull(selectedChat)
    }

    @Test
    fun repeatedNotificationTaps_keepLatestConversationWhenCreateCompletes() {
        val groupA = "group-a"
        val groupB = "group-b"
        val groupC = "group-c"

        var state = ShellNavigationState()
        var selectedChat: String? = null
        val submit = reduceShellNavigation(state, ShellNavigationEvent.CreateSubmitted)
        state = submit.state
        val (afterBState, afterB) =
            reduceAndApply(
                state,
                selectedChat,
                ShellNavigationEvent.ExplicitConversationOpened(groupB),
            )
        state = afterBState
        selectedChat = afterB
        val (afterCState, afterC) =
            reduceAndApply(
                state,
                selectedChat,
                ShellNavigationEvent.ExplicitConversationOpened(groupC),
            )
        state = afterCState
        selectedChat = afterC
        val (_, afterComplete, complete) =
            completeCreate(
                state,
                selectedChat,
                groupA,
                submit.createRequestTokenMinted!!,
            )
        selectedChat = afterComplete

        assertFalse(complete.createOpenAccepted)
        assertEquals(groupC, selectedChat)
    }

    @Test
    fun createCompletionAfterAuthoritativeListRowWouldStillNavigateWhenUninterrupted() {
        val groupA = "group-a"

        var state = ShellNavigationState()
        var selectedChat: String? = null
        val submit = reduceShellNavigation(state, ShellNavigationEvent.CreateSubmitted)
        state = submit.state
        // Authoritative chat-list row arrival does not change shell navigation generation.
        val (_, afterComplete, complete) =
            completeCreate(
                state,
                selectedChat,
                groupA,
                submit.createRequestTokenMinted!!,
            )
        selectedChat = afterComplete

        assertTrue(complete.createOpenAccepted)
        assertEquals(groupA, selectedChat)
    }

    @Test
    fun supersededCreateThenNewCreate_rejectsStaleCompletionAndAcceptsCurrent() {
        val groupA = "group-a"
        val groupB = "group-b"
        val groupC = "group-c"

        var state = ShellNavigationState()
        var selectedChat: String? = null
        val submitA = reduceShellNavigation(state, ShellNavigationEvent.CreateSubmitted)
        state = submitA.state
        val (afterOpenState, afterOpen) =
            reduceAndApply(
                state,
                selectedChat,
                ShellNavigationEvent.ExplicitConversationOpened(groupB),
            )
        state = afterOpenState
        selectedChat = afterOpen
        val submitC = reduceShellNavigation(state, ShellNavigationEvent.CreateSubmitted)
        state = submitC.state

        val (afterStaleState, afterStale, staleComplete) =
            completeCreate(
                state,
                selectedChat,
                groupA,
                submitA.createRequestTokenMinted!!,
            )
        assertFalse(staleComplete.createOpenAccepted)
        assertEquals(groupB, afterStale)
        assertEquals(submitC.createRequestTokenMinted, staleComplete.state.pendingCreateRequestToken)

        val (_, afterCurrent, currentComplete) =
            completeCreate(
                afterStaleState,
                afterStale,
                groupC,
                submitC.createRequestTokenMinted!!,
            )
        selectedChat = afterCurrent
        assertTrue(currentComplete.createOpenAccepted)
        assertEquals(groupC, selectedChat)
    }

    @Test
    fun explicitOpenSameChatThenStaleCreateCompletion_rejectsWithoutSecondOpen() {
        val groupA = "group-a"

        var state = ShellNavigationState()
        var selectedChat: String? = null
        val submit = reduceShellNavigation(state, ShellNavigationEvent.CreateSubmitted)
        state = submit.state
        val (afterOpenState, afterOpen) =
            reduceAndApply(
                state,
                selectedChat,
                ShellNavigationEvent.ExplicitConversationOpened(groupA),
            )
        state = afterOpenState
        selectedChat = afterOpen
        val (_, afterComplete, complete) =
            completeCreate(
                state,
                selectedChat,
                groupA,
                submit.createRequestTokenMinted!!,
            )
        selectedChat = afterComplete

        assertFalse(complete.createOpenAccepted)
        assertEquals(groupA, selectedChat)
    }

    @Test
    fun retryCompletionUsesOriginalSubmitToken() {
        val groupA = "group-a"
        val groupB = "group-b"

        var state = ShellNavigationState()
        var selectedChat: String? = null
        val submit = reduceShellNavigation(state, ShellNavigationEvent.CreateSubmitted)
        state = submit.state
        val originalToken = submit.createRequestTokenMinted!!

        // Authoritative-read retry does not re-arm ownership.
        val (_, afterComplete, complete) =
            completeCreate(state, selectedChat, groupA, originalToken)
        selectedChat = afterComplete
        assertTrue(complete.createOpenAccepted)
        assertEquals(groupA, selectedChat)

        // A superseding explicit open invalidates the same token for a late retry.
        state = ShellNavigationState()
        selectedChat = null
        val submitAfterFail = reduceShellNavigation(state, ShellNavigationEvent.CreateSubmitted)
        state = submitAfterFail.state
        val retryToken = submitAfterFail.createRequestTokenMinted!!
        val (afterOpenState, afterOpen) =
            reduceAndApply(
                state,
                selectedChat,
                ShellNavigationEvent.ExplicitConversationOpened(groupB),
            )
        state = afterOpenState
        selectedChat = afterOpen
        val (_, afterLateRetry, lateRetry) =
            completeCreate(state, selectedChat, groupA, retryToken)
        selectedChat = afterLateRetry
        assertFalse(lateRetry.createOpenAccepted)
        assertEquals(groupB, selectedChat)
    }

    @Test
    fun createSubmittedMintsMonotonicallyIncreasingTokens() {
        var state = ShellNavigationState()
        val first = reduceShellNavigation(state, ShellNavigationEvent.CreateSubmitted)
        state = first.state
        val second = reduceShellNavigation(state, ShellNavigationEvent.CreateSubmitted)

        assertTrue(first.createRequestTokenMinted!! < second.createRequestTokenMinted!!)
        assertEquals(second.createRequestTokenMinted, second.state.pendingCreateRequestToken)
    }
}
