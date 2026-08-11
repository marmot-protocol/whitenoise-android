package dev.ipf.whitenoise.android.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral regressions for issue #1953 interleavings that source scans missed.
 */
class ShellNavigationInterleavingTest {
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

    @Test
    fun profileArmedWhileProfileNewGroupActive_clearsForegroundRouteBeforeProfileShows() {
        val foreground = ProfileGroupForegroundState()
        foreground.open(viewedCandidate())

        assertEquals(
            ProfileForegroundRoute.NewGroup(viewedCandidate()),
            profileForegroundRoute(
                pendingProfileNpub = TARGET_NPROFILE,
                startGroupMember = foreground.initialMember,
                conversationOpen = false,
            ),
        )

        armShellProfileForeground(ShellNavigationState(), foreground)

        assertNull(foreground.initialMember)
        assertEquals(
            ProfileForegroundRoute.ShellProfile(TARGET_NPROFILE),
            profileForegroundRoute(
                pendingProfileNpub = TARGET_NPROFILE,
                startGroupMember = foreground.initialMember,
                conversationOpen = false,
            ),
        )
    }

    @Test
    fun createSubmittedThenProfileArmedWithForegroundThenCreateCompleted_rejectsStaleOpen() {
        val groupA = "group-a"
        val foreground = ProfileGroupForegroundState()
        foreground.open(viewedCandidate())

        var state = ShellNavigationState()
        var selectedChat: String? = null
        val submit = reduceShellNavigation(state, ShellNavigationEvent.CreateSubmitted)
        state = submit.state
        state = armShellProfileForeground(state, foreground)
        val completeEvent =
            ShellNavigationEvent.CreateCompleted(groupA, submit.createRequestTokenMinted!!)
        val transition = reduceShellNavigation(state, completeEvent)
        selectedChat = applyShellSelection(selectedChat, completeEvent, transition)

        assertFalse(transition.createOpenAccepted)
        assertNull(selectedChat)
        assertNull(foreground.initialMember)
        assertNull(transition.state.pendingCreateRequestToken)
    }

    @Test
    fun createSubmittedThenNotificationArmedThenCreateCompleted_rejectsStaleOpen() {
        val groupA = "group-a"

        var state = ShellNavigationState()
        var selectedChat: String? = null
        val submit = reduceShellNavigation(state, ShellNavigationEvent.CreateSubmitted)
        state = submit.state
        val foreground = ProfileGroupForegroundState()
        foreground.open(viewedCandidate())
        state = armShellNotificationRequest(state, foreground)
        val completeEvent =
            ShellNavigationEvent.CreateCompleted(groupA, submit.createRequestTokenMinted!!)
        val transition = reduceShellNavigation(state, completeEvent)
        selectedChat = applyShellSelection(selectedChat, completeEvent, transition)

        assertFalse(transition.createOpenAccepted)
        assertNull(selectedChat)
        assertNull(foreground.initialMember)
    }

    @Test
    fun notificationArmedWhileProfileNewGroupActive_clearsForegroundRouteBeforeConversationOpens() {
        val foreground = ProfileGroupForegroundState()
        foreground.open(viewedCandidate())

        assertEquals(
            ProfileForegroundRoute.NewGroup(viewedCandidate()),
            profileForegroundRoute(
                pendingProfileNpub = null,
                startGroupMember = foreground.initialMember,
                conversationOpen = true,
            ),
        )

        armShellNotificationRequest(ShellNavigationState(), foreground)

        assertNull(foreground.initialMember)
        assertEquals(
            ProfileForegroundRoute.None,
            profileForegroundRoute(
                pendingProfileNpub = null,
                startGroupMember = foreground.initialMember,
                conversationOpen = true,
            ),
        )
    }

    @Test
    fun notificationArmedThenExplicitOpenThenCreateCompleted_keepsNotificationChat() {
        val groupA = "group-a"
        val groupB = "group-b"

        var state = ShellNavigationState()
        var selectedChat: String? = null
        val submit = reduceShellNavigation(state, ShellNavigationEvent.CreateSubmitted)
        state = submit.state
        val requestToken = submit.createRequestTokenMinted!!
        state = armShellNotificationRequest(state, ProfileGroupForegroundState())
        val (afterArm, _) =
            reduceAndApply(
                state,
                selectedChat,
                ShellNavigationEvent.ExplicitConversationOpened(groupB),
            )
        state = afterArm
        selectedChat = groupB
        val completeTransition =
            reduceShellNavigation(
                state,
                ShellNavigationEvent.CreateCompleted(groupA, requestToken),
            )
        selectedChat =
            applyShellSelection(
                selectedChat,
                ShellNavigationEvent.CreateCompleted(groupA, requestToken),
                completeTransition,
            )

        assertFalse(completeTransition.createOpenAccepted)
        assertEquals(groupB, selectedChat)
    }

    @Test
    fun uninterruptedCreateCompletion_stillOpensCreatedGroup() {
        val groupA = "group-a"

        var state = ShellNavigationState()
        var selectedChat: String? = null
        val submit = reduceShellNavigation(state, ShellNavigationEvent.CreateSubmitted)
        state = submit.state
        val requestToken = submit.createRequestTokenMinted!!
        val transition =
            reduceShellNavigation(
                state,
                ShellNavigationEvent.CreateCompleted(groupA, requestToken),
            )
        selectedChat =
            applyShellSelection(
                selectedChat,
                ShellNavigationEvent.CreateCompleted(groupA, requestToken),
                transition,
            )

        assertTrue(transition.createOpenAccepted)
        assertEquals(groupA, selectedChat)
    }

    private fun viewedCandidate() =
        dev.ipf.whitenoise.android.core.RecipientSearch.Candidate(
            accountIdHex = "a".repeat(64),
            displayName = "Alice",
            npub = "npub1alice",
        )

    private companion object {
        const val TARGET_NPROFILE = "nprofile-test-alice"
    }
}
