package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.audio.tts.TtsConversationDestination
import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            TtsDestinationNavigationStep.AwaitAccountSwitch,
            resolve(
                activeAccount = "account-b",
                navigationRequest = request.copy(accountSwitchRequested = true),
            ),
        )
    }

    @Test
    fun passageAdvanceWhileAccountSwitchIsPendingKeepsWaitingForTheOwnedSwitch() {
        assertEquals(
            TtsDestinationNavigationStep.AwaitAccountSwitch,
            resolve(
                current = destination.copy(passage = TtsPassage("message-newer", sentenceIndex = 2)),
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

    @Test
    fun onlyTheCurrentRequestOwnsAnAsyncCompletion() {
        assertTrue(request.ownsCompletion(requestId = 3L))
        assertFalse(request.ownsCompletion(requestId = 2L))
        assertFalse(request.copy(requestId = 4L).ownsCompletion(requestId = 3L))
        assertFalse((null as TtsDestinationNavigationRequest?).ownsCompletion(requestId = 3L))
    }

    @Test
    fun onlyTheRequestOwnedAccountTransitionKeepsRoutingAlive() {
        val ownership =
            TtsDestinationAccountSwitchOwnership(
                requestId = request.requestId,
                sourceAccountRef = "account-b",
                targetAccountRef = "account-a",
            )

        assertTrue(ownership.ownsAccountChange("account-b", "account-a", request))
        assertFalse(ownership.ownsAccountChange("account-b", "account-c", request))
        assertFalse(ownership.ownsAccountChange("account-b", "account-a", request.copy(requestId = 4L)))
        assertFalse(ownership.ownsAccountChange("account-c", "account-a", request))
    }

    /** Voice return shares exact account/group validation while retaining its independent player token. */
    @Test fun voiceDestinationUsesCurrentMessageAndRejectsReplacedPlayer() {
        val source =
            dev.ipf.whitenoise.android.audio
                .VoicePlaybackSource("account-a", "group-a", "voice-message", "Maya")
        val voice =
            dev.ipf.whitenoise.android.audio
                .VoiceConversationDestination(source, 7)
        val voiceRequest = request.copy(sessionId = voice.sessionId)

        fun route(
            current: dev.ipf.whitenoise.android.audio.PlaybackConversationDestination?,
            active: String,
        ) = resolveTtsDestinationNavigation(
            voiceRequest,
            current,
            setOf("account-a", "account-b"),
            active,
            setOf("group-a"),
        )
        assertEquals(TtsDestinationNavigationStep.SwitchAccount("account-a"), route(voice, "account-b"))
        assertEquals(
            TtsDestinationNavigationStep.OpenConversation("group-a", "voice-message", -7, request.requestId),
            route(voice, "account-a"),
        )
        assertEquals(TtsDestinationNavigationStep.Cancelled, route(voice.copy(playerSessionId = 8), "account-a"))
        assertEquals(TtsDestinationNavigationStep.Cancelled, route(destination.copy(sessionId = 7), "account-a"))
        assertEquals(TtsDestinationNavigationStep.Cancelled, route(null, "account-a"))
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
