package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.audio.tts.TtsConversationDestination
import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsDestinationNavigationTest {
    private val destination =
        TtsConversationDestination(
            accountRef = "account-a",
            groupIdHex = "group-a",
            sessionId = 9L,
            passage = TtsPassage("message-newest", sentenceIndex = 1),
        )
    private val request = TtsDestinationNavigationRequest(3L, "account-a", "group-a", 9L)

    @Test
    fun staleOrReplacedSessionCancelsWithoutRouting() {
        assertEquals(TtsDestinationNavigationStep.Cancelled, resolve(current = null))
        assertEquals(
            TtsDestinationNavigationStep.Cancelled,
            resolve(current = destination.copy(sessionId = 10L)),
        )
        assertEquals(
            TtsDestinationNavigationStep.Cancelled,
            resolve(current = destination.copy(groupIdHex = "group-b")),
        )
    }

    @Test
    fun sourceAccountIsValidatedAndSwitchedBeforeChatResolution() {
        assertEquals(
            TtsDestinationNavigationStep.MissingAccount,
            resolve(knownAccounts = emptySet()),
        )
        assertEquals(
            TtsDestinationNavigationStep.SwitchAccount("account-a"),
            resolve(activeAccount = "account-b"),
        )
        assertEquals(
            TtsDestinationNavigationStep.AccountSwitchSuperseded,
            resolve(
                activeAccount = "account-b",
                navigationRequest = request.copy(accountSwitchRequested = true),
            ),
        )
    }

    @Test
    fun latestPassageIsUsedForExistingAndDirectlyLoadedConversations() {
        assertEquals(
            TtsDestinationNavigationStep.OpenConversation(
                groupIdHex = "group-a",
                messageIdHex = "message-newest",
                sessionId = 9L,
                requestId = 3L,
            ),
            resolve(availableGroups = setOf("GROUP-A")),
        )
        assertEquals(
            TtsDestinationNavigationStep.LoadConversationDirectly(
                accountRef = "account-a",
                groupIdHex = "group-a",
                messageIdHex = "message-newest",
                sessionId = 9L,
                requestId = 3L,
            ),
            resolve(availableGroups = emptySet()),
        )
    }

    private fun resolve(
        current: TtsConversationDestination? = destination,
        knownAccounts: Set<String> = setOf("account-a"),
        activeAccount: String? = "account-a",
        availableGroups: Set<String> = setOf("group-a"),
        navigationRequest: TtsDestinationNavigationRequest = request,
    ): TtsDestinationNavigationStep =
        resolveTtsDestinationNavigation(
            request = navigationRequest,
            currentDestination = current,
            knownAccountRefs = knownAccounts,
            activeAccountRef = activeAccount,
            availableGroupIds = availableGroups,
        )
}
